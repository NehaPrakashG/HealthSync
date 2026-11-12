package dev.neha.healthsync.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.neha.healthsync.domain.model.HealthConnectAvailability
import dev.neha.healthsync.domain.model.HealthException
import dev.neha.healthsync.domain.repository.HealthRepository
import dev.neha.healthsync.domain.repository.SyncRepository
import kotlinx.coroutines.CancellationException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic background sync. Runs only when the user granted background reads;
 * otherwise sync happens when the app is opened (foreground).
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val healthRepository: HealthRepository,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (healthRepository.availability() != HealthConnectAvailability.AVAILABLE) return Result.success()

        return try {
            val access = healthRepository.permissionSnapshot()
            if (!access.backgroundReadGranted || access.grantedFeatures.isEmpty()) {
                return Result.success() // Nothing we're allowed to do in the background.
            }
            syncRepository.sync()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HealthException.PermissionMissing) {
            Result.success() // Revoked: retrying won't help. Next app open shows the reconnect UI.
        } catch (e: HealthException.Unavailable) {
            Result.success()
        } catch (e: HealthException.InvalidData) {
            Result.failure()
        } catch (e: Exception) {
            // Transient Health Connect errors (incl. rate limits) and network failures.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "health_connect_sync"
        const val MAX_ATTEMPTS = 5
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(Duration.ofHours(1))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HealthSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
