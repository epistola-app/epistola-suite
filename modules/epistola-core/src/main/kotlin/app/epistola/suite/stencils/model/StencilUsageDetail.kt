package app.epistola.suite.stencils.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

/**
 * Detailed usage info for a stencil across template versions.
 * Used by the bulk upgrade UI to show which templates need upgrading.
 */
data class StencilUsageDetail(
    val templateId: TemplateKey,
    val catalogKey: CatalogKey,
    val catalogType: CatalogType = CatalogType.AUTHORED,
    val templateName: String,
    val variantId: VariantKey,
    val versionId: VersionKey,
    val versionStatus: String,
    val stencilVersion: Int,
    /** Number of instances of this stencil in this template version. */
    val instanceCount: Int,
)
