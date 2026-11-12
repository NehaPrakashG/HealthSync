package dev.neha.healthsync.domain.model

import java.time.Instant
import java.time.LocalDate

/** Whether Health Connect can be used on this device right now. */
enum class HealthConnectAvailability { AVAILABLE, UPDATE_REQUIRED, NOT_SUPPORTED }

/**
 * A user-facing feature backed by one Health Connect data type.
 * Permissions are granted per feature, so the UI renders per feature.
 */
enum class HealthFeature { STEPS, HEART_RATE }

data class PermissionSnapshot(
    val grantedFeatures: Set<HealthFeature>,
    val backgroundReadSupported: Boolean,
    val backgroundReadGranted: Boolean,
    val historyReadGranted: Boolean,
) {
    fun isGranted(feature: HealthFeature): Boolean = feature in grantedFeatures
}

data class DailySteps(val date: LocalDate, val count: Long)

data class StepsSummary(
    val today: Long,
    val last7Days: List<DailySteps>,
)

data class HeartRateSummary(
    val latestBpm: Long?,
    val latestAt: Instant?,
    val avgBpm: Long?,
    val minBpm: Long?,
    val maxBpm: Long?,
)

/** A single reading, e.g. received from a wearable over BLE. */
data class HeartRateReading(val time: Instant, val bpm: Long)

data class SyncStatus(
    val lastSyncAt: Instant? = null,
    val pendingUploads: Int = 0,
    val uploadedRecords: Int = 0,
    val lastError: String? = null,
)

data class SyncReport(
    val imported: Int,
    val deleted: Int,
    val skippedOwn: Int,
    val uploaded: Int,
    val fullResync: Boolean,
)
