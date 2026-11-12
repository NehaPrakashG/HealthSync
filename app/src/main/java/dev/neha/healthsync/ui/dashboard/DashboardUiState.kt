package dev.neha.healthsync.ui.dashboard

import dev.neha.healthsync.domain.model.HeartRateSummary
import dev.neha.healthsync.domain.model.StepsSummary
import dev.neha.healthsync.domain.model.SyncStatus

/** Single immutable state object rendered by the screen. */
data class DashboardUiState(
    val availability: AvailabilityUi = AvailabilityUi.Checking,
    val steps: FeatureUi<StepsSummary> = FeatureUi.Loading,
    val heartRate: FeatureUi<HeartRateSummary> = FeatureUi.Loading,
    val access: AccessUi = AccessUi(),
    val sync: SyncStatus = SyncStatus(),
    val isSyncing: Boolean = false,
)

enum class AvailabilityUi { Checking, Available, UpdateRequired, NotSupported }

/**
 * Each feature has its own state, so a denied or failing feature never blocks the others
 * (user grants steps but denies heart rate -> steps still render).
 */
sealed interface FeatureUi<out T> {
    data object Loading : FeatureUi<Nothing>
    data class NotGranted(val deniedTwice: Boolean) : FeatureUi<Nothing>
    data class Failed(val message: String) : FeatureUi<Nothing>
    data class Ready<T>(val data: T) : FeatureUi<T>
}

data class AccessUi(
    val backgroundSupported: Boolean = false,
    val backgroundGranted: Boolean = false,
    val historyGranted: Boolean = false,
)

/** One-off events (not state): consumed once by the UI. */
sealed interface DashboardEvent {
    data class ShowMessage(val message: String) : DashboardEvent
}
