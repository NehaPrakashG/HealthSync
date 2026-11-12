package dev.neha.healthsync.data.remote

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** Our backend. Uploads are idempotent: the server dedupes on the Health Connect record id. */
interface HealthBackendApi {

    @POST("v1/health/records")
    suspend fun upsertRecords(@Body body: UpsertRecordsRequest): UploadResponse

    @POST("v1/health/records/delete")
    suspend fun deleteRecords(@Body body: DeleteRecordsRequest): UploadResponse
}

@Serializable
data class RecordDto(
    val id: String,
    val type: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val value: Double,
    val sampleCount: Int,
    val source: String,
)

@Serializable
data class UpsertRecordsRequest(val records: List<RecordDto>)

@Serializable
data class DeleteRecordsRequest(val ids: List<String>)

@Serializable
data class UploadResponse(val accepted: Int)

/** Stand-in backend so the app runs end-to-end without a server. */
class FakeHealthBackendApi : HealthBackendApi {
    override suspend fun upsertRecords(body: UpsertRecordsRequest): UploadResponse {
        delay(300)
        return UploadResponse(accepted = body.records.size)
    }

    override suspend fun deleteRecords(body: DeleteRecordsRequest): UploadResponse {
        delay(150)
        return UploadResponse(accepted = body.ids.size)
    }
}
