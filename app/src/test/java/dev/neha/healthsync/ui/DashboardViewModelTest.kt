package dev.neha.healthsync.ui

import dev.neha.healthsync.data.simulator.SimulatedWearable
import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthException
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.HeartRateReading
import dev.neha.healthsync.domain.model.HeartRateSummary
import dev.neha.healthsync.domain.model.PermissionSnapshot
import dev.neha.healthsync.domain.model.StepsSummary
import dev.neha.healthsync.domain.model.SyncReport
import dev.neha.healthsync.domain.model.SyncStatus
import dev.neha.healthsync.domain.repository.HealthRepository
import dev.neha.healthsync.domain.repository.SyncRepository
import dev.neha.healthsync.fakes.FakeTimeProvider
import dev.neha.healthsync.ui.dashboard.AvailabilityUi
import dev.neha.healthsync.ui.dashboard.DashboardEvent
import dev.neha.healthsync.ui.dashboard.DashboardViewModel
import dev.neha.healthsync.ui.dashboard.FeatureUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val health = FakeHealthRepository()
    private val sync = FakeSyncRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = DashboardViewModel(health, sync, SimulatedWearable(FakeTimeProvider()))

    @Test
    fun `unsupported device shows not supported`() {
        health.availability = HealthConnectAvailability.NOT_SUPPORTED
        val vm = viewModel()

        vm.refresh()

        assertEquals(AvailabilityUi.NotSupported, vm.uiState.value.availability)
    }

    @Test
    fun `partial grant renders steps and asks for heart rate`() {
        health.granted = setOf(HealthFeature.STEPS)
        health.stepsToday = 1234
        val vm = viewModel()

        vm.refresh()

        val state = vm.uiState.value
        assertEquals(AvailabilityUi.Available, state.availability)
        assertEquals(1234L, (state.steps as FeatureUi.Ready).data.today)
        assertEquals(FeatureUi.NotGranted(deniedTwice = false), state.heartRate)
    }

    @Test
    fun `two denials switch the CTA to settings`() {
        val vm = viewModel()

        repeat(2) {
            vm.permissionRequestFor(HealthFeature.HEART_RATE)
            vm.onPermissionResult(granted = emptySet())
        }

        assertEquals(FeatureUi.NotGranted(deniedTwice = true), vm.uiState.value.heartRate)
        assertEquals(FeatureUi.NotGranted(deniedTwice = false), vm.uiState.value.steps)
    }

    @Test
    fun `permission revoked during read falls back to not granted`() {
        health.granted = setOf(HealthFeature.STEPS)
        health.stepsError = HealthException.PermissionMissing()
        val vm = viewModel()

        vm.refresh()

        assertTrue(vm.uiState.value.steps is FeatureUi.NotGranted)
    }

    @Test
    fun `one failing feature does not break the other`() {
        health.granted = setOf(HealthFeature.STEPS, HealthFeature.HEART_RATE)
        health.stepsError = HealthException.Transient(RuntimeException("busy"))
        val vm = viewModel()

        vm.refresh()

        assertTrue(vm.uiState.value.steps is FeatureUi.Failed)
        assertTrue(vm.uiState.value.heartRate is FeatureUi.Ready)
    }

    @Test
    fun `logging steps writes, reports and reloads`() = runTest {
        health.granted = setOf(HealthFeature.STEPS)
        val vm = viewModel()

        vm.logSteps()

        assertEquals(500L, health.loggedSteps)
        assertEquals(DashboardEvent.ShowMessage("Logged 500 steps"), vm.events.first())
        assertEquals(500L, (vm.uiState.value.steps as FeatureUi.Ready).data.today)
    }

    @Test
    fun `sync status flows into ui state`() {
        val vm = viewModel()

        sync.statusFlow.value = SyncStatus(pendingUploads = 7)

        assertEquals(7, vm.uiState.value.sync.pendingUploads)
    }
}

private class FakeHealthRepository : HealthRepository {
    var availability = HealthConnectAvailability.AVAILABLE
    var granted: Set<HealthFeature> = emptySet()
    var stepsToday = 0L
    var stepsError: Exception? = null
    var loggedSteps = 0L

    override fun availability() = availability
    override fun permissionsFor(feature: HealthFeature) = setOf("read_${feature.name}", "write_${feature.name}")
    override fun extendedAccessPermissions() = setOf("background", "history")

    override suspend fun permissionSnapshot() = PermissionSnapshot(
        grantedFeatures = granted,
        backgroundReadSupported = true,
        backgroundReadGranted = false,
        historyReadGranted = false,
    )

    override suspend fun stepsSummary(): StepsSummary {
        stepsError?.let { throw it }
        return StepsSummary(stepsToday, emptyList())
    }

    override suspend fun heartRateSummary() = HeartRateSummary(72, null, 70, 60, 90)

    override suspend fun logManualSteps(count: Long, duration: Duration) {
        loggedSteps += count
        stepsToday += count
    }

    override suspend fun recordHeartRate(readings: List<HeartRateReading>) = Unit
}

private class FakeSyncRepository : SyncRepository {
    val statusFlow = MutableStateFlow(SyncStatus())
    override val status = statusFlow
    override suspend fun sync() = SyncReport(0, 0, 0, 0, false)
}
