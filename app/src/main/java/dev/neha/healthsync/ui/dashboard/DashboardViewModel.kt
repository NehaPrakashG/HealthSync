package dev.neha.healthsync.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.neha.healthsync.data.simulator.SimulatedWearable
import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthException
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.PermissionSnapshot
import dev.neha.healthsync.domain.model.SyncReport
import dev.neha.healthsync.domain.model.toUserMessage
import dev.neha.healthsync.domain.repository.HealthRepository
import dev.neha.healthsync.domain.repository.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val healthRepository: HealthRepository,
    private val syncRepository: SyncRepository,
    private val wearable: SimulatedWearable,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _events = Channel<DashboardEvent>(Channel.BUFFERED)
    val events: Flow<DashboardEvent> = _events.receiveAsFlow()

    private var refreshJob: Job? = null

    // Permission bookkeeping. After two denials Android stops showing the dialog,
    // so we switch the CTA to "Open Health Connect settings".
    private var pendingRequest: PendingRequest? = null
    private val denials = mutableMapOf<HealthFeature, Int>()

    init {
        viewModelScope.launch {
            syncRepository.status.collect { status -> _uiState.update { it.copy(sync = status) } }
        }
    }

    /** Called on every ON_RESUME: permissions may have changed while we were in the background. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { load() }
    }

    // ------------------------------------------------------------- Permissions

    fun permissionRequestFor(feature: HealthFeature): Set<String> =
        healthRepository.permissionsFor(feature).also { pendingRequest = PendingRequest(feature, it) }

    fun extendedAccessRequest(): Set<String> =
        healthRepository.extendedAccessPermissions().also { pendingRequest = PendingRequest(null, it) }

    fun onPermissionResult(granted: Set<String>) {
        val request = pendingRequest ?: return refresh()
        pendingRequest = null
        val feature = request.feature
        if (feature != null && !granted.containsAll(request.permissions)) {
            denials[feature] = (denials[feature] ?: 0) + 1
        }
        refresh()
    }

    // ----------------------------------------------------------------- Actions

    fun logSteps() {
        runAction(success = "Logged 500 steps") {
            healthRepository.logManualSteps(count = 500, duration = Duration.ofMinutes(30))
        }
    }

    fun simulateWatchReading() {
        runAction(success = "Saved 5 heart-rate samples from your watch") {
            healthRepository.recordHeartRate(wearable.lastFiveMinutes())
        }
    }

    fun syncNow() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            val message = try {
                syncRepository.sync().toMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Sync failed: ${e.toUserMessage()}"
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
            _events.send(DashboardEvent.ShowMessage(message))
        }
    }

    // ----------------------------------------------------------------- Loading

    private suspend fun load() {
        val availability = healthRepository.availability()
        if (availability != HealthConnectAvailability.AVAILABLE) {
            _uiState.update { it.copy(availability = availability.toUi()) }
            return
        }

        val snapshot = try {
            healthRepository.permissionSnapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = FeatureUi.Failed(e.toUserMessage())
            _uiState.update { it.copy(availability = AvailabilityUi.Available, steps = failed, heartRate = failed) }
            return
        }

        // Features load in parallel and fail independently.
        coroutineScope {
            val steps = async { loadFeature(HealthFeature.STEPS, snapshot) { healthRepository.stepsSummary() } }
            val heartRate = async { loadFeature(HealthFeature.HEART_RATE, snapshot) { healthRepository.heartRateSummary() } }
            val stepsState = steps.await()
            val heartRateState = heartRate.await()
            _uiState.update {
                it.copy(
                    availability = AvailabilityUi.Available,
                    steps = stepsState,
                    heartRate = heartRateState,
                    access = AccessUi(
                        backgroundSupported = snapshot.backgroundReadSupported,
                        backgroundGranted = snapshot.backgroundReadGranted,
                        historyGranted = snapshot.historyReadGranted,
                    ),
                )
            }
        }
    }

    private suspend fun <T> loadFeature(
        feature: HealthFeature,
        snapshot: PermissionSnapshot,
        loader: suspend () -> T,
    ): FeatureUi<T> {
        if (!snapshot.isGranted(feature)) return notGranted(feature)
        return try {
            FeatureUi.Ready(loader())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HealthException.PermissionMissing) {
            notGranted(feature) // revoked between the permission check and the read
        } catch (e: Exception) {
            FeatureUi.Failed(e.toUserMessage())
        }
    }

    private fun notGranted(feature: HealthFeature) =
        FeatureUi.NotGranted(deniedTwice = (denials[feature] ?: 0) >= 2)

    private fun runAction(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val message = try {
                block()
                success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toUserMessage()
            }
            _events.send(DashboardEvent.ShowMessage(message))
            load()
        }
    }

    private fun HealthConnectAvailability.toUi() = when (this) {
        HealthConnectAvailability.AVAILABLE -> AvailabilityUi.Available
        HealthConnectAvailability.UPDATE_REQUIRED -> AvailabilityUi.UpdateRequired
        HealthConnectAvailability.NOT_SUPPORTED -> AvailabilityUi.NotSupported
    }

    private fun SyncReport.toMessage(): String = buildString {
        append(if (fullResync) "Full import: " else "Synced: ")
        append("$imported new, $deleted deleted, $uploaded uploaded")
        if (skippedOwn > 0) append(" ($skippedOwn own records skipped)")
    }

    private data class PendingRequest(val feature: HealthFeature?, val permissions: Set<String>)
}
