package dev.neha.healthsync.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Time abstraction. Zone is read on every call (not cached) so travel and
 * DST changes are picked up, and tests can control "now".
 */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId

    fun today(): LocalDate = now().atZone(zone()).toLocalDate()
    fun startOfToday(): Instant = today().atStartOfDay(zone()).toInstant()
    fun offsetAt(instant: Instant): ZoneOffset = zone().rules.getOffset(instant)
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
