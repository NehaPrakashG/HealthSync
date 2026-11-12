package dev.neha.healthsync.data.healthconnect

import dev.neha.healthsync.domain.model.HealthException
import dev.neha.healthsync.domain.model.HeartRateReading
import java.time.Instant

/**
 * Validates data before it reaches Health Connect. Health Connect rejects invalid
 * records anyway; validating first gives clear errors and never crashes on bad
 * device input (e.g. a BLE sensor sending 0 bpm).
 */
object RecordValidator {

    const val MIN_BPM = 1L
    const val MAX_BPM = 300L
    const val MAX_STEPS_PER_RECORD = 1_000_000L

    fun validateSteps(start: Instant, end: Instant, count: Long, now: Instant) {
        ensure(start < end) { "Steps: start must be before end" }
        ensure(!end.isAfter(now)) { "Steps: end time cannot be in the future" }
        ensure(count in 1..MAX_STEPS_PER_RECORD) { "Steps: count $count is out of range" }
    }

    fun validateHeartRate(readings: List<HeartRateReading>, now: Instant) {
        ensure(readings.isNotEmpty()) { "Heart rate: no samples" }
        readings.forEach { reading ->
            ensure(reading.bpm in MIN_BPM..MAX_BPM) { "Heart rate: ${reading.bpm} bpm is out of range" }
            ensure(!reading.time.isAfter(now)) { "Heart rate: sample time cannot be in the future" }
        }
        ensure(readings.zipWithNext().all { (a, b) -> !a.time.isAfter(b.time) }) {
            "Heart rate: samples must be in chronological order"
        }
    }

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) throw HealthException.InvalidData(message())
    }
}
