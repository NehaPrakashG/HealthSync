package dev.neha.healthsync.data.repository

import dev.neha.healthsync.core.TimeProvider
import dev.neha.healthsync.data.healthconnect.HealthDataSource
import dev.neha.healthsync.data.local.HealthRecordDao
import dev.neha.healthsync.data.local.SyncStateStore
import dev.neha.healthsync.data.remote.DeleteRecordsRequest
import dev.neha.healthsync.data.remote.HealthBackendApi
import dev.neha.healthsync.data.remote.UpsertRecordsRequest
import dev.neha.healthsync.data.toDto
import dev.neha.healthsync.data.toEntity
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.SyncReport
import dev.neha.healthsync.domain.model.SyncStatus
import dev.neha.healthsync.domain.model.toUserMessage
import dev.neha.healthsync.domain.repository.SyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-phase sync:
 *  1. PULL  Health Connect -> Room (changes token; full import on first run or token expiry)
 *  2. PUSH  Room outbox -> backend (batched, idempotent, retried by WorkManager)
 */
@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val dataSource: HealthDataSource,
    private val dao: HealthRecordDao,
    private val stateStore: SyncStateStore,
    private val api: HealthBackendApi,
    private val time: TimeProvider,
) : SyncRepository {

    // The worker and the "Sync now" button share this singleton; never run two syncs at once.
    private val mutex = Mutex()

    override val status: Flow<SyncStatus> = combine(
        stateStore.lastSyncAt,
        stateStore.lastError,
        dao.observePendingCount(),
        dao.observeUploadedCount(),
    ) { lastSyncAt, lastError, pending, uploaded ->
        SyncStatus(lastSyncAt, pending, uploaded, lastError)
    }

    override suspend fun sync(): SyncReport = mutex.withLock {
        try {
            val features = dataSource.readableFeatures()
            val pull = if (features.isEmpty()) PullResult.EMPTY else pull(features)
            val uploaded = push()
            stateStore.markSucceeded(time.now())
            SyncReport(pull.imported, pull.deleted, pull.skippedOwn, uploaded, pull.fullResync)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stateStore.markFailed(e.toUserMessage())
            throw e
        }
    }

    // ----------------------------------------------------------------- PULL

    private suspend fun pull(features: Set<HealthFeature>): PullResult {
        val scope = scopeKey(features)
        val token = stateStore.changesToken(scope)
        return if (token == null) fullImport(features, scope) else incremental(features, scope, token)
    }

    private suspend fun fullImport(features: Set<HealthFeature>, scope: String): PullResult {
        // Take the token BEFORE reading, so anything written during the import is caught next time.
        val token = dataSource.getChangesToken(features)

        val now = time.now()
        val window = if (dataSource.hasHistoryAccess()) HISTORY_WINDOW else DEFAULT_WINDOW
        val earliestAllowed = now.minus(window)
        // Re-read from the last sync (with overlap) when we have one; Room's primary key dedupes.
        val since = stateStore.lastSyncAt()?.minus(OVERLAP)?.let { maxOf(it, earliestAllowed) } ?: earliestAllowed

        // Full import keeps our own records too (e.g. after reinstall they exist in HC but not in Room).
        val records = dataSource.readRecords(features, since, now)
        if (records.isNotEmpty()) dao.upsert(records.map { it.toEntity() })

        stateStore.saveToken(scope, token)
        return PullResult(imported = records.size, deleted = 0, skippedOwn = 0, fullResync = true)
    }

    private suspend fun incremental(features: Set<HealthFeature>, scope: String, startToken: String): PullResult {
        var token = startToken
        var imported = 0
        var deleted = 0
        var skippedOwn = 0

        while (true) {
            val page = dataSource.getChanges(token)

            if (page.tokenExpired) {
                // Token unused for 30+ days: fall back to a time-range re-read.
                stateStore.clearToken(scope)
                return fullImport(features, scope)
            }

            // Our own writes are already in the outbox; re-importing them would loop.
            val (own, external) = page.upserts.partition { it.originPackage == dataSource.ownPackageName }
            if (external.isNotEmpty()) dao.upsert(external.map { it.toEntity() })
            if (page.deletedIds.isNotEmpty()) dao.markDeleted(page.deletedIds)

            imported += external.size
            deleted += page.deletedIds.size
            skippedOwn += own.size

            token = page.nextToken
            stateStore.saveToken(scope, token) // checkpoint per page: a crash mid-sync resumes here
            if (!page.hasMore) break
        }
        return PullResult(imported, deleted, skippedOwn, fullResync = false)
    }

    // ----------------------------------------------------------------- PUSH

    private suspend fun push(): Int {
        var uploaded = 0
        while (true) {
            val batch = dao.pendingUpserts(BATCH_SIZE)
            if (batch.isEmpty()) break
            api.upsertRecords(UpsertRecordsRequest(batch.map { it.toDto() }))
            dao.markUploaded(batch.map { it.healthConnectId })
            uploaded += batch.size
        }
        while (true) {
            val ids = dao.pendingDeletes(BATCH_SIZE)
            if (ids.isEmpty()) break
            api.deleteRecords(DeleteRecordsRequest(ids))
            dao.deleteByIds(ids)
        }
        return uploaded
    }

    private fun scopeKey(features: Set<HealthFeature>) = features.map { it.name }.sorted().joinToString(",")

    private data class PullResult(val imported: Int, val deleted: Int, val skippedOwn: Int, val fullResync: Boolean) {
        companion object {
            val EMPTY = PullResult(0, 0, 0, false)
        }
    }

    companion object {
        const val BATCH_SIZE = 100
        val DEFAULT_WINDOW: Duration = Duration.ofDays(30)
        val HISTORY_WINDOW: Duration = Duration.ofDays(365)
        val OVERLAP: Duration = Duration.ofHours(1)
    }
}
