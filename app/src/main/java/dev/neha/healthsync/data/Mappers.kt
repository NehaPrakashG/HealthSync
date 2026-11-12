package dev.neha.healthsync.data

import dev.neha.healthsync.data.healthconnect.HealthRecordSnapshot
import dev.neha.healthsync.data.local.HealthRecordEntity
import dev.neha.healthsync.data.local.UploadState
import dev.neha.healthsync.data.remote.RecordDto

fun HealthRecordSnapshot.toEntity(uploadState: String = UploadState.PENDING) = HealthRecordEntity(
    healthConnectId = id,
    feature = feature.name,
    startEpochMs = start.toEpochMilli(),
    endEpochMs = end.toEpochMilli(),
    value = value,
    sampleCount = sampleCount,
    originPackage = originPackage,
    lastModifiedEpochMs = lastModified.toEpochMilli(),
    uploadState = uploadState,
)

fun HealthRecordEntity.toDto() = RecordDto(
    id = healthConnectId,
    type = feature,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    value = value,
    sampleCount = sampleCount,
    source = originPackage,
)
