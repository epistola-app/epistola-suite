// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

class MutableClock(
    private val initialInstant: Instant = Instant.parse("2026-06-10T00:00:00Z"),
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    private val current = AtomicReference(initialInstant)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant(), zone)

    override fun instant(): Instant = current.get()

    fun set(instant: Instant) {
        current.set(instant)
    }

    fun reset(instant: Instant = initialInstant) {
        current.set(instant)
    }

    fun advanceBy(duration: Duration): Instant = current.updateAndGet { it.plus(duration) }
}
