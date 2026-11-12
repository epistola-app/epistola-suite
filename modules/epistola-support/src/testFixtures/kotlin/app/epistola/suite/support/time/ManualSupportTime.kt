// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.time

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * A [SupportTime] that stands still until a test [advance]s it: a budget runs out when the test says
 * so, not when a slow machine takes long enough. Thread-safe, as the support work reads it from its
 * own threads.
 */
class ManualSupportTime : SupportTime {
    private val nanos = AtomicLong(0)

    override fun nanoTime(): Long = nanos.get()

    fun advance(duration: Duration) {
        nanos.addAndGet(duration.toNanos())
    }
}

/**
 * Puts a [ManualSupportTime] in the context in place of the system one; `@Import` it, and autowire
 * [ManualSupportTime] to advance it. Not picked up by component scanning, being a `@TestConfiguration`.
 */
@TestConfiguration(proxyBeanMethods = false)
class ManualSupportTimeConfiguration {
    @Bean
    @Primary
    fun manualSupportTime(): ManualSupportTime = ManualSupportTime()
}
