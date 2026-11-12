package dev.neha.healthsync.domain.repository

import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthFeature
import dev.neha.healthsync.domain.model.HeartRateReading
import dev.neha.healthsync.domain.model.HeartRateSummary
import dev.neha.healthsync.domain.model.PermissionSnapshot
import dev.neha.healthsync.domain.model.StepsSummary
import dev.neha.healthsync.domain.model.SyncReport
import dev.neha.healthsync.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import java.time.Duration

/** Reads and writes health data. The UI depends on this, never on HealthConnectClient. */
interface HealthRepository {
    fun availability(): HealthConnectAvailability

    /** Platform permission strings to pass to the Health Connect permission contract. */
    fun permissionsFor(feature: HealthFeature): Set<String>
    fun extendedAccessPermissions(): Set<String>

    suspend fun permissionSnapshot(): PermissionSnapshot

    suspend fun stepsSummary(): StepsSummary
    suspend fun heartRateSummary(): HeartRateSummary

    suspend fun logManualSteps(count: Long, duration: Duration)
    suspend fun recordHeartRate(readings: List<HeartRateReading>)
}

/** Pulls changes from Health Connect into the local outbox and pushes them to the backend. */
interface SyncRepository {
    val status: Flow<SyncStatus>
    suspend fun sync(): SyncReport
}
