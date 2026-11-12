package dev.neha.healthsync

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.neha.healthsync.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class HealthSyncApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // KEEP policy: safe to call on every launch. The worker no-ops without background permission.
        syncScheduler.schedulePeriodicSync()
    }
}
