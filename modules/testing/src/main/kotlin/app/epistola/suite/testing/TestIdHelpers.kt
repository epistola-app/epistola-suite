package app.epistola.suite.testing

import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test helpers for generating unique IDs in tests.
 * These helpers create unique, sequential IDs to avoid conflicts in tests.
 */
object TestIdHelpers {
    private val templateIdCounter = AtomicInteger(0)
    private val variantIdCounter = AtomicInteger(0)
    private val environmentIdCounter = AtomicInteger(0)
    private val attributeIdCounter = AtomicInteger(0)
    private val stencilIdCounter = AtomicInteger(0)

    /**
     * Generates a unique TemplateId for testing purposes.
     * Each call returns a different ID in the format: test-template-{N}
     */
    fun nextTemplateId(): TemplateKey = TemplateKey.of("test-template-${templateIdCounter.incrementAndGet()}")

    /**
     * Generates a unique VariantId for testing purposes.
     * Each call returns a different ID in the format: test-variant-{N}
     */
    fun nextVariantId(): VariantKey = VariantKey.of("test-variant-${variantIdCounter.incrementAndGet()}")

    /**
     * Generates a unique EnvironmentId for testing purposes.
     * Each call returns a different ID in the format: test-env-{N}
     */
    fun nextEnvironmentId(): EnvironmentKey = EnvironmentKey.of("test-env-${environmentIdCounter.incrementAndGet()}")

    /**
     * Generates a unique AttributeId for testing purposes.
     * Each call returns a different ID in the format: test-attr-{N}
     */
    fun nextAttributeId(): AttributeKey = AttributeKey.of("test-attr-${attributeIdCounter.incrementAndGet()}")

    fun nextStencilId(): StencilKey = StencilKey.of("test-stencil-${stencilIdCounter.incrementAndGet()}")

    /**
     * Resets all counters. Use this in test setup if needed.
     */
    fun reset() {
        templateIdCounter.set(0)
        variantIdCounter.set(0)
        environmentIdCounter.set(0)
        attributeIdCounter.set(0)
        stencilIdCounter.set(0)
    }
}
