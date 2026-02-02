package app.epistola.suite.common

import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TemplateId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test helpers for generating unique IDs in tests.
 * These helpers create unique, sequential IDs to avoid conflicts in tests.
 */
object TestIdHelpers {
    private val templateIdCounter = AtomicInteger(0)

    /**
     * Generates a unique TemplateId for testing purposes.
     * Each call returns a different ID in the format: test-template-{N}
     */
    fun nextTemplateId(): TemplateId = TemplateId.of("test-template-${templateIdCounter.incrementAndGet()}")

    /**
     * Resets all counters. Use this in test setup if needed.
     */
    fun reset() {
        templateIdCounter.set(0)
    }
}
