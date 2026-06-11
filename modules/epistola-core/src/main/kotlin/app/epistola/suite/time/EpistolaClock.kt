package app.epistola.suite.time

import app.epistola.suite.mediator.MediatorContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

object EpistolaClock {
    private val scopedClock: ScopedValue<Clock> = ScopedValue.newInstance()
    private val systemClock: Clock = Clock.systemUTC()

    fun current(): Clock = when {
        scopedClock.isBound -> scopedClock.get()
        else -> MediatorContext.currentClockOrNull() ?: systemClock
    }

    fun capture(): Clock = current()

    fun instant(): Instant = current().instant()

    fun offsetDateTime(): OffsetDateTime = OffsetDateTime.now(current())

    fun localDate(zone: ZoneId = ZoneId.of("UTC")): LocalDate = LocalDate.now(current().withZone(zone))

    fun yearMonth(zone: ZoneId = ZoneId.of("UTC")): YearMonth = YearMonth.now(current().withZone(zone))

    fun <T> withClock(
        clock: Clock,
        block: () -> T,
    ): T = ScopedValue.where(scopedClock, clock).call<T, RuntimeException>(block)

    fun <T> withInstant(
        instant: Instant,
        zone: ZoneId = ZoneId.of("UTC"),
        block: () -> T,
    ): T = withClock(Clock.fixed(instant, zone), block)
}
