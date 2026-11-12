package dev.neha.healthsync.fakes

import dev.neha.healthsync.core.TimeProvider
import dev.neha.healthsync.data.healthconnect.ChangesPage
import dev.neha.healthsync.data.healthconnect.HealthDataSource
import dev.neha.healthsync.data.healthconnect.HealthRecordSnapshot
import dev.neha.healthsync.data.healthconnect.HeartRateStats
import dev.neha.healthsync.data.local.HealthRecordDao
import dev.neha.healthsync.data.local.HealthRecordEntity
import dev.neha.healthsync.data.local.SyncStateStore
import dev.neha.healthsync.data.local.UploadState
import dev.neha.healthsync.data.remote.DeleteRecordsRequest
import dev.neha.healthsync.data.remote.HealthBackendApi
import dev.neha.healthsync.data.remote.UploadResponse
import dev.neha.healthsync.data.remote.UpsertRecordsRequest
import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.HeartRateReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

const val OWN_PACKAGE = "dev.neha.healthsync"
const val OTHER_PACKAGE = "com.fitbit.FitbitMobile"

class FakeTimeProvider(var instant: Instant = Instant.parse("2026-11-12T18:00:00Z")) : TimeProvider {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = ZoneOffset.UTC
}

fun snapshot(
    id: String,
    origin: String = OTHER_PACKAGE,
    feature: HealthFeature = HealthFeature.STEPS,
    value: Double = 100.0,
) = HealthRecordSnapshot(
    id = id,
    feature = feature,
    start = Instant.parse("2026-11-12T10:00:00Z"),
    end = Instant.parse("2026-11-12T10:30:00Z"),
    value = value,
    sampleCount = 1,
    originPackage = origin,
    lastModified = Instant.parse("2026-11-12T10:30:00Z"),
)

class FakeHealthDataSource : HealthDataSource {
    override val ownPackageName = OWN_PACKAGE

    var readable: Set<HealthFeature> = HealthFeature.entries.toSet()
    var history = false
    var records: List<HealthRecordSnapshot> = emptyList()
    val changePages = ArrayDeque<ChangesPage>()
    var tokenCounter = 0
    val readRanges = mutableListOf<Pair<Instant, Instant>>()

    override fun availability() = HealthConnectAvailability.AVAILABLE
    override suspend fun grantedPermissions(): Set<String> = emptySet()
    override suspend fun isBackgroundReadSupported() = true
    override suspend fun readableFeatures() = readable
    override suspend fun hasHistoryAccess() = history
    override suspend fun aggregateSteps(start: Instant, end: Instant) = 0L
    override suspend fun dailySteps(startDate: LocalDate, endDateExclusive: LocalDate) = emptyMap<LocalDate, Long>()
    override suspend fun heartRateStats(start: Instant, end: Instant) = HeartRateStats(null, null, null)
    override suspend fun latestHeartRate(start: Instant, end: Instant): HeartRateReading? = null

    override suspend fun readRecords(features: Set<HealthFeature>, start: Instant, end: Instant): List<HealthRecordSnapshot> {
        readRanges += start to end
        return records.filter { it.feature in features }
    }

    override suspend fun insertSteps(start: Instant, end: Instant, count: Long, clientRecordId: String) = "hc-$clientRecordId"
    override suspend fun insertHeartRate(readings: List<HeartRateReading>, clientRecordId: String) = "hc-$clientRecordId"

    override suspend fun getChangesToken(features: Set<HealthFeature>): String = "token-${++tokenCounter}"
    override suspend fun getChanges(token: String): ChangesPage = changePages.removeFirst()
}

class FakeHealthRecordDao : HealthRecordDao {
    private val rows = MutableStateFlow<Map<String, HealthRecordEntity>>(emptyMap())
    val all: Map<String, HealthRecordEntity> get() = rows.value

    override suspend fun upsert(records: List<HealthRecordEntity>) {
        rows.value = rows.value + records.associateBy { it.healthConnectId }
    }

    override suspend fun markDeleted(ids: List<String>) = setState(ids, UploadState.PENDING_DELETE)

    override suspend fun pendingUpserts(limit: Int) =
        rows.value.values.filter { it.uploadState == UploadState.PENDING }.take(limit)

    override suspend fun pendingDeletes(limit: Int) =
        rows.value.values.filter { it.uploadState == UploadState.PENDING_DELETE }.map { it.healthConnectId }.take(limit)

    override suspend fun markUploaded(ids: List<String>) = setState(ids, UploadState.UPLOADED)

    override suspend fun deleteByIds(ids: List<String>) {
        rows.value = rows.value - ids.toSet()
    }

    override fun observePendingCount(): Flow<Int> = rows.map { m -> m.values.count { it.uploadState != UploadState.UPLOADED } }
    override fun observeUploadedCount(): Flow<Int> = rows.map { m -> m.values.count { it.uploadState == UploadState.UPLOADED } }

    private fun setState(ids: List<String>, state: String) {
        rows.value = rows.value.mapValues { (id, row) -> if (id in ids) row.copy(uploadState = state) else row }
    }
}

class FakeSyncStateStore : SyncStateStore {
    val tokens = mutableMapOf<String, String>()
    private val lastSync = MutableStateFlow<Instant?>(null)
    private val error = MutableStateFlow<String?>(null)

    override val lastSyncAt: Flow<Instant?> = lastSync
    override val lastError: Flow<String?> = error

    override suspend fun changesToken(scopeKey: String) = tokens[scopeKey]
    override suspend fun saveToken(scopeKey: String, token: String) { tokens[scopeKey] = token }
    override suspend fun clearToken(scopeKey: String) { tokens.remove(scopeKey) }
    override suspend fun lastSyncAt(): Instant? = lastSync.value
    override suspend fun markSucceeded(at: Instant) { lastSync.value = at; error.value = null }
    override suspend fun markFailed(message: String) { error.value = message }

    fun setLastSync(at: Instant) { lastSync.value = at }
    val currentError: String? get() = error.value
}

class FakeBackendApi : HealthBackendApi {
    var failWith: Exception? = null
    val uploadedIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()

    override suspend fun upsertRecords(body: UpsertRecordsRequest): UploadResponse {
        failWith?.let { throw it }
        uploadedIds += body.records.map { it.id }
        return UploadResponse(body.records.size)
    }

    override suspend fun deleteRecords(body: DeleteRecordsRequest): UploadResponse {
        failWith?.let { throw it }
        deletedIds += body.ids
        return UploadResponse(body.ids.size)
    }

    companion object {
        fun networkError() = IOException("offline")
    }
}
