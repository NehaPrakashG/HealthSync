package dev.neha.healthsync.data.simulator

import dev.neha.healthsync.core.TimeProvider
import dev.neha.healthsync.domain.model.HeartRateReading
import java.time.Duration
import javax.inject.Inject
import kotlin.random.Random

/**
 * Stands in for a BLE heart-rate strap / watch. In a real app this would be a
 * BLE GATT client emitting readings (Heart Rate Measurement characteristic 0x2A37).
 */
class SimulatedWearable @Inject constructor(private val time: TimeProvider) {

    fun lastFiveMinutes(): List<HeartRateReading> {
        val now = time.now()
        return (4L downTo 0L).map { minutesAgo ->
            HeartRateReading(
                time = now.minus(Duration.ofMinutes(minutesAgo)).minusSeconds(1),
                bpm = Random.nextLong(58, 112),
            )
        }
    }
}
