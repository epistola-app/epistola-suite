package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey

/**
 * Aggregated generation statistics for a tenant.
 */
data class GenerationStats(
    val totalGenerated: Long,
    val inQueue: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
)

/**
 * Template usage frequency entry.
 */
data class TemplateUsage(
    val templateKey: TemplateKey,
    val variantKey: VariantKey,
    val count: Long,
)
