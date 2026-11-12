package dev.neha.healthsync.data.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import dev.neha.healthsync.domain.model.HealthFeature
import kotlin.reflect.KClass

/** Single source of truth for which Health Connect types/permissions back each feature. */
object HealthPermissions {

    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    val BACKGROUND_READ = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    val HISTORY_READ = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    fun recordType(feature: HealthFeature): KClass<out Record> = when (feature) {
        HealthFeature.STEPS -> StepsRecord::class
        HealthFeature.HEART_RATE -> HeartRateRecord::class
    }

    fun read(feature: HealthFeature): String = HealthPermission.getReadPermission(recordType(feature))

    fun write(feature: HealthFeature): String = HealthPermission.getWritePermission(recordType(feature))

    fun forFeature(feature: HealthFeature): Set<String> = setOf(read(feature), write(feature))

    val extendedAccess: Set<String> = setOf(BACKGROUND_READ, HISTORY_READ)
}
