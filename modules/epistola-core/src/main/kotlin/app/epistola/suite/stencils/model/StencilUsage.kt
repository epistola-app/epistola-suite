package app.epistola.suite.stencils.model

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

/**
 * Describes where a stencil is used within a template version.
 */
data class StencilUsage(
    val templateId: TemplateKey,
    val templateName: String,
    val variantId: VariantKey,
    val versionId: VersionKey,
    val stencilVersion: Int,
)
