package dev.neha.healthsync.data

import dev.neha.healthsync.data.healthconnect.RecordValidator
import dev.neha.healthsync.domain.model.HealthException
import dev.neha.healthsync.domain.model.HeartRateReading
import org.junit.Test
import java.time.Instant

class RecordValidatorTest {

    private val now = Instant.parse("2026-11-12T18:00:00Z")

    @Test
    fun `valid steps pass`() {
        RecordValidator.validateSteps(now.minusSeconds(600), now, 500, now)
    }

    @Test(expected = HealthException.InvalidData::class)
    fun `steps ending in the future are rejected`() {
        RecordValidator.validateSteps(now, now.plusSeconds(60), 500, now)
    }

    @Test(expected = HealthException.InvalidData::class)
    fun `steps with start after end are rejected`() {
        RecordValidator.validateSteps(now, now.minusSeconds(60), 500, now)
    }

    @Test(expected = HealthException.InvalidData::class)
    fun `zero steps are rejected`() {
        RecordValidator.validateSteps(now.minusSeconds(60), now, 0, now)
    }

    @Test(expected = HealthException.InvalidData::class)
    fun `zero bpm from a faulty sensor is rejected`() {
        RecordValidator.validateHeartRate(listOf(HeartRateReading(now, 0)), now)
    }

    @Test(expected = HealthException.InvalidData::class)
    fun `out of order samples are rejected`() {
        RecordValidator.validateHeartRate(
            listOf(HeartRateReading(now, 80), HeartRateReading(now.minusSeconds(60), 82)),
            now,
        )
    }

    @Test
    fun `valid heart rate passes`() {
        RecordValidator.validateHeartRate(
            listOf(HeartRateReading(now.minusSeconds(60), 72), HeartRateReading(now, 75)),
            now,
        )
    }
}
