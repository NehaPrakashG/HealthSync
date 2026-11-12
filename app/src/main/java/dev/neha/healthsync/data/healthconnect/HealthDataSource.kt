package dev.neha.healthsync.data.healthconnect

import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.HeartRateReading
import java.time.Instant
import java.time.LocalDate

/**
 * Thin, testable boundary over HealthConnectClient.
 * Returns plain Kotlin models (no Health Connect Record types leak upward),
 * and throws only [dev.neha.healthsync.domain.model.HealthException].
 */
interface HealthDataSource {
    val ownPackageName: String

    fun availability(): HealthConnectAvailability
    suspend fun grantedPermissions(): Set<String>
    suspend fun isBackgroundReadSupported(): Boolean

    /** Features the app may READ right now (sync only touches these). */
    suspend fun readableFeatures(): Set<HealthFeature>
    suspend fun hasHistoryAccess(): Boolean

    // Reads ------------------------------------------------------------------
    suspend fun aggregateSteps(start: Instant, end: Instant): Long
    suspend fun dailySteps(startDate: LocalDate, endDateExclusive: LocalDate): Map<LocalDate, Long>
    suspend fun heartRateStats(start: Instant, end: Instant): HeartRateStats
    suspend fun latestHeartRate(start: Instant, end: Instant): HeartRateReading?
    suspend fun readRecords(features: Set<HealthFeature>, start: Instant, end: Instant): List<HealthRecordSnapshot>

    // Writes (return the Health Connect record id) ---------------------------
    suspend fun insertSteps(start: Instant, end: Instant, count: Long, clientRecordId: String): String
    suspend fun insertHeartRate(readings: List<HeartRateReading>, clientRecordId: String): String

    // Changes API ------------------------------------------------------------
    suspend fun getChangesToken(features: Set<HealthFeature>): String
    suspend fun getChanges(token: String): ChangesPage
}

data class HeartRateStats(val avg: Long?, val min: Long?, val max: Long?)

/** Platform-agnostic copy of a Health Connect record, used for sync and upload. */
data class HealthRecordSnapshot(
    val id: String,
    val feature: HealthFeature,
    val start: Instant,
    val end: Instant,
    /** Steps: count. Heart rate: average bpm across samples. */
    val value: Double,
    val sampleCount: Int,
    val originPackage: String,
    val lastModified: Instant,
)

data class ChangesPage(
    val upserts: List<HealthRecordSnapshot>,
    val deletedIds: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    val tokenExpired: Boolean,
)
