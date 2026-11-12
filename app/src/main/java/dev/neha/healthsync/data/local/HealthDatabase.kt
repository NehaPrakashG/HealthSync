package dev.neha.healthsync.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Outbox state of a record relative to our backend. */
object UploadState {
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
    const val PENDING_DELETE = "PENDING_DELETE"
}

/**
 * Local mirror of Health Connect records + upload outbox.
 * Primary key is the Health Connect record id, so re-importing the same record
 * (after token expiry, overlap windows, retries) dedupes automatically.
 */
@Entity(tableName = "health_records")
data class HealthRecordEntity(
    @PrimaryKey val healthConnectId: String,
    val feature: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val value: Double,
    val sampleCount: Int,
    val originPackage: String,
    val lastModifiedEpochMs: Long,
    val uploadState: String,
)

@Dao
interface HealthRecordDao {

    @Upsert
    suspend fun upsert(records: List<HealthRecordEntity>)

    @Query("UPDATE health_records SET uploadState = 'PENDING_DELETE' WHERE healthConnectId IN (:ids)")
    suspend fun markDeleted(ids: List<String>)

    @Query("SELECT * FROM health_records WHERE uploadState = 'PENDING' ORDER BY startEpochMs LIMIT :limit")
    suspend fun pendingUpserts(limit: Int): List<HealthRecordEntity>

    @Query("SELECT healthConnectId FROM health_records WHERE uploadState = 'PENDING_DELETE' LIMIT :limit")
    suspend fun pendingDeletes(limit: Int): List<String>

    @Query("UPDATE health_records SET uploadState = 'UPLOADED' WHERE healthConnectId IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("DELETE FROM health_records WHERE healthConnectId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM health_records WHERE uploadState != 'UPLOADED'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM health_records WHERE uploadState = 'UPLOADED'")
    fun observeUploadedCount(): Flow<Int>
}

@Database(entities = [HealthRecordEntity::class], version = 1, exportSchema = false)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthRecordDao(): HealthRecordDao
}
