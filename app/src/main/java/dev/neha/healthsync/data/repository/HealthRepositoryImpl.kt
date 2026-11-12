package dev.neha.healthsync.data.repository

import dev.neha.healthsync.core.TimeProvider
import dev.neha.healthsync.data.healthconnect.HealthDataSource
import dev.neha.healthsync.data.healthconnect.HealthPermissions
import dev.neha.healthsync.data.healthconnect.HealthRecordSnapshot
import dev.neha.healthsync.data.healthconnect.RecordValidator
import dev.neha.healthsync.data.local.HealthRecordDao
import dev.neha.healthsync.data.toEntity
import dev.neha.healthsync.domain.model.DailySteps
import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.HeartRateReading
import dev.neha.healthsync.domain.model.HeartRateSummary
import dev.neha.healthsync.domain.model.PermissionSnapshot
import dev.neha.healthsync.domain.model.StepsSummary
import dev.neha.healthsync.domain.repository.HealthRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepositoryImpl @Inject constructor(
    private val dataSource: HealthDataSource,
    private val dao: HealthRecordDao,
    private val time: TimeProvider,
) : HealthRepository {

    override fun availability(): HealthConnectAvailability = dataSource.availability()

    override fun permissionsFor(feature: HealthFeature): Set<String> = HealthPermissions.forFeature(feature)

    override fun extendedAccessPermissions(): Set<String> = HealthPermissions.extendedAccess

    /** Always fetched fresh: the user can revoke access from Settings at any time. */
    override suspend fun permissionSnapshot(): PermissionSnapshot {
        val granted = dataSource.grantedPermissions()
        return PermissionSnapshot(
            grantedFeatures = HealthFeature.entries
                .filter { granted.containsAll(HealthPermissions.forFeature(it)) }
                .toSet(),
            backgroundReadSupported = dataSource.isBackgroundReadSupported(),
            backgroundReadGranted = HealthPermissions.BACKGROUND_READ in granted,
            historyReadGranted = HealthPermissions.HISTORY_READ in granted,
        )
    }

    override suspend fun stepsSummary(): StepsSummary = coroutineScope {
        val today = time.today()
        val todayTotal = async { dataSource.aggregateSteps(time.startOfToday(), time.now()) }
        val daily = async { dataSource.dailySteps(today.minusDays(6), today.plusDays(1)) }

        // Days with no data are omitted by Health Connect; fill them with zero.
        val byDate = daily.await()
        StepsSummary(
            today = todayTotal.await(),
            last7Days = (6L downTo 0L).map { offset ->
                val date = today.minusDays(offset)
                DailySteps(date, byDate[date] ?: 0L)
            },
        )
    }

    override suspend fun heartRateSummary(): HeartRateSummary = coroutineScope {
        val start = time.startOfToday()
        val now = time.now()
        val stats = async { dataSource.heartRateStats(start, now) }
        val latest = async { dataSource.latestHeartRate(start, now) }
        val s = stats.await()
        val l = latest.await()
        HeartRateSummary(latestBpm = l?.bpm, latestAt = l?.time, avgBpm = s.avg, minBpm = s.min, maxBpm = s.max)
    }

    override suspend fun logManualSteps(count: Long, duration: Duration) {
        val end = time.now()
        val start = end.minus(duration)
        RecordValidator.validateSteps(start, end, count, now = end)

        val clientRecordId = "steps-${start.toEpochMilli()}-${end.toEpochMilli()}"
        val id = dataSource.insertSteps(start, end, count, clientRecordId)

        // Own writes go straight into the outbox; incremental sync skips them to avoid re-importing.
        dao.upsert(
            listOf(
                HealthRecordSnapshot(
                    id = id,
                    feature = HealthFeature.STEPS,
                    start = start,
                    end = end,
                    value = count.toDouble(),
                    sampleCount = 1,
                    originPackage = dataSource.ownPackageName,
                    lastModified = end,
                ).toEntity()
            )
        )
    }

    override suspend fun recordHeartRate(readings: List<HeartRateReading>) {
        val sorted = readings.sortedBy { it.time }
        RecordValidator.validateHeartRate(sorted, now = time.now())

        val clientRecordId = "hr-${sorted.first().time.toEpochMilli()}"
        val id = dataSource.insertHeartRate(sorted, clientRecordId)

        dao.upsert(
            listOf(
                HealthRecordSnapshot(
                    id = id,
                    feature = HealthFeature.HEART_RATE,
                    start = sorted.first().time,
                    end = sorted.last().time.plusSeconds(1),
                    value = sorted.map { it.bpm }.average(),
                    sampleCount = sorted.size,
                    originPackage = dataSource.ownPackageName,
                    lastModified = time.now(),
                ).toEntity()
            )
        )
    }
}
