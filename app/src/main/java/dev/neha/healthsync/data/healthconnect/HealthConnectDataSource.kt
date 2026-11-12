package dev.neha.healthsync.data.healthconnect

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.neha.healthsync.core.TimeProvider
import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthException
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.HeartRateReading
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class HealthConnectDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeProvider,
) : HealthDataSource {

    // getOrCreate() throws if Health Connect is unavailable, so create lazily, after availability().
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override val ownPackageName: String = context.packageName

    override fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context, HealthPermissions.PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.NOT_SUPPORTED
        }

    override suspend fun grantedPermissions(): Set<String> = call {
        client.permissionController.getGrantedPermissions()
    }

    override suspend fun isBackgroundReadSupported(): Boolean = call {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    override suspend fun readableFeatures(): Set<HealthFeature> {
        val granted = grantedPermissions()
        return HealthFeature.entries.filter { HealthPermissions.read(it) in granted }.toSet()
    }

    override suspend fun hasHistoryAccess(): Boolean = HealthPermissions.HISTORY_READ in grantedPermissions()

    // ---------------------------------------------------------------- Reads

    /** Aggregate, not a manual sum: Health Connect removes phone/watch overlap using the user's app priority. */
    override suspend fun aggregateSteps(start: Instant, end: Instant): Long = call {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        result[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    /** Period-based buckets use LocalDateTime, so "a day" follows the user's local calendar (DST/travel safe). */
    override suspend fun dailySteps(startDate: LocalDate, endDateExclusive: LocalDate): Map<LocalDate, Long> = call {
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    startDate.atStartOfDay(),
                    endDateExclusive.atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            )
        ).associate { bucket ->
            bucket.startTime.toLocalDate() to (bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L)
        }
    }

    override suspend fun heartRateStats(start: Instant, end: Instant): HeartRateStats = call {
        val result = client.aggregate(
            AggregateRequest(
                metrics = setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            )
        )
        HeartRateStats(
            avg = result[HeartRateRecord.BPM_AVG],
            min = result[HeartRateRecord.BPM_MIN],
            max = result[HeartRateRecord.BPM_MAX],
        )
    }

    override suspend fun latestHeartRate(start: Instant, end: Instant): HeartRateReading? = call {
        val latestRecord = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = false,
                pageSize = 1,
            )
        ).records.firstOrNull()
        latestRecord?.samples?.maxByOrNull { it.time }?.let { HeartRateReading(it.time, it.beatsPerMinute) }
    }

    override suspend fun readRecords(
        features: Set<HealthFeature>,
        start: Instant,
        end: Instant,
    ): List<HealthRecordSnapshot> = call {
        features.flatMap { feature ->
            readAllPages(HealthPermissions.recordType(feature), start, end).mapNotNull { it.toSnapshot() }
        }
    }

    /** Large ranges are paginated; never assume one page holds everything. */
    private suspend fun <T : Record> readAllPages(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                )
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    // --------------------------------------------------------------- Writes

    /** clientRecordId makes writes idempotent: re-inserting the same id updates instead of duplicating. */
    override suspend fun insertSteps(start: Instant, end: Instant, count: Long, clientRecordId: String): String = call {
        val record = StepsRecord(
            startTime = start,
            startZoneOffset = time.offsetAt(start),
            endTime = end,
            endZoneOffset = time.offsetAt(end),
            count = count,
            metadata = Metadata.manualEntry(clientRecordId = clientRecordId),
        )
        client.insertRecords(listOf(record)).recordIdsList.first()
    }

    override suspend fun insertHeartRate(readings: List<HeartRateReading>, clientRecordId: String): String = call {
        val start = readings.first().time
        // Interval end is exclusive, so pad past the last sample.
        val end = readings.last().time.plusSeconds(1)
        val record = HeartRateRecord(
            startTime = start,
            startZoneOffset = time.offsetAt(start),
            endTime = end,
            endZoneOffset = time.offsetAt(end),
            samples = readings.map { HeartRateRecord.Sample(time = it.time, beatsPerMinute = it.bpm) },
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = clientRecordId,
            ),
        )
        client.insertRecords(listOf(record)).recordIdsList.first()
    }

    // ---------------------------------------------------------- Changes API

    override suspend fun getChangesToken(features: Set<HealthFeature>): String = call {
        client.getChangesToken(
            ChangesTokenRequest(recordTypes = features.map { HealthPermissions.recordType(it) }.toSet())
        )
    }

    override suspend fun getChanges(token: String): ChangesPage = call {
        val response = client.getChanges(token)
        ChangesPage(
            upserts = response.changes.filterIsInstance<UpsertionChange>().mapNotNull { it.record.toSnapshot() },
            deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId },
            nextToken = response.nextChangesToken,
            hasMore = response.hasMore,
            tokenExpired = response.changesTokenExpired,
        )
    }

    // -------------------------------------------------------------- Helpers

    private fun Record.toSnapshot(): HealthRecordSnapshot? = when (this) {
        is StepsRecord -> HealthRecordSnapshot(
            id = metadata.id,
            feature = HealthFeature.STEPS,
            start = startTime,
            end = endTime,
            value = count.toDouble(),
            sampleCount = 1,
            originPackage = metadata.dataOrigin.packageName,
            lastModified = metadata.lastModifiedTime,
        )
        is HeartRateRecord -> if (samples.isEmpty()) null else HealthRecordSnapshot(
            id = metadata.id,
            feature = HealthFeature.HEART_RATE,
            start = startTime,
            end = endTime,
            value = samples.map { it.beatsPerMinute }.average(),
            sampleCount = samples.size,
            originPackage = metadata.dataOrigin.packageName,
            lastModified = metadata.lastModifiedTime,
        )
        else -> null
    }

    /** Maps platform exceptions to domain errors in one place. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HealthException) {
        throw e
    } catch (e: SecurityException) {
        throw HealthException.PermissionMissing(e)
    } catch (e: IllegalArgumentException) {
        throw HealthException.InvalidData(e.message ?: "Record rejected by Health Connect")
    } catch (e: IllegalStateException) {
        throw HealthException.Unavailable(e)
    } catch (e: RemoteException) {
        // Includes quota / rate-limit errors from the Health Connect service.
        throw HealthException.Transient(e)
    } catch (e: IOException) {
        throw HealthException.Transient(e)
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
