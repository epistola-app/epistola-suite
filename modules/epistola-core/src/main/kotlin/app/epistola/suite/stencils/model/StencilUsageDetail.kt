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
    /**
     * Whether this row is the single bulk-upgrade target for its variant.
     *
     * Upgrading always lands in the variant's draft (created from the latest
     * published version when none exists), so exactly one row per variant is
     * actionable: the draft if the variant has one, otherwise its latest
     * published version. Other rows of the same variant (and subscribed/archived
     * rows) are not upgradable. Computed by [app.epistola.suite.stencils.queries.GetStencilUsageDetails].
     */
    val upgradable: Boolean = false,
    /**
     * Why this row is not upgradable, for the UI to explain to the operator.
     * Null exactly when [upgradable] is true.
     */
    val upgradeBlockReason: UpgradeBlockReason? = null,
) {
    /** Reason a usage row cannot be the bulk-upgrade target. */
    enum class UpgradeBlockReason {
        /** Belongs to a subscribed (read-only) catalog. */
        SUBSCRIBED,

        /** The variant already has an open draft, which is the upgrade target instead. */
        HAS_DRAFT,

        /** A newer version of the variant is the upgrade target (this one is superseded/archived). */
        SUPERSEDED,
    }
}
