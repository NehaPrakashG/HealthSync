package dev.neha.healthsync.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.neha.healthsync.BuildConfig
import dev.neha.healthsync.core.SystemTimeProvider
import dev.neha.healthsync.core.TimeProvider
import dev.neha.healthsync.data.healthconnect.HealthConnectDataSource
import dev.neha.healthsync.data.healthconnect.HealthDataSource
import dev.neha.healthsync.data.local.DataStoreSyncStateStore
import dev.neha.healthsync.data.local.HealthDatabase
import dev.neha.healthsync.data.local.HealthRecordDao
import dev.neha.healthsync.data.local.SyncStateStore
import dev.neha.healthsync.data.remote.FakeHealthBackendApi
import dev.neha.healthsync.data.remote.HealthBackendApi
import dev.neha.healthsync.data.repository.HealthRepositoryImpl
import dev.neha.healthsync.data.repository.SyncRepositoryImpl
import dev.neha.healthsync.domain.repository.HealthRepository
import dev.neha.healthsync.domain.repository.SyncRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
    @Binds abstract fun bindHealthDataSource(impl: HealthConnectDataSource): HealthDataSource
    @Binds abstract fun bindSyncStateStore(impl: DataStoreSyncStateStore): SyncStateStore
    @Binds abstract fun bindHealthRepository(impl: HealthRepositoryImpl): HealthRepository
    @Binds abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HealthDatabase =
        Room.databaseBuilder(context, HealthDatabase::class.java, "health_sync.db").build()

    @Provides
    fun provideHealthRecordDao(db: HealthDatabase): HealthRecordDao = db.healthRecordDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideBackendApi(): HealthBackendApi {
        if (BuildConfig.USE_FAKE_BACKEND) return FakeHealthBackendApi()

        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HealthBackendApi::class.java)
    }
}
