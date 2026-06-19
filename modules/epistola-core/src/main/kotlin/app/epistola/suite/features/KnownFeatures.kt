package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val SUPPORT_FEEDBACK = FeatureKey.of("support-feedback")
    val SUPPORT_BACKUPS = FeatureKey.of("support-backups")

    /**
     * The compatibility-check feature (Support → Upgrading). The toggle/entitlement key is
     * `support-compatibility-check`; the UI/module is still named "upgrading" internally pending a
     * follow-up rename.
     */
    val SUPPORT_COMPATIBILITY_CHECK = FeatureKey.of("support-compatibility-check")
    val STENCIL_PARAMETERS = FeatureKey.of("stencil-parameters")

    val all: List<FeatureKey> = listOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_COMPATIBILITY_CHECK, STENCIL_PARAMETERS)

    /**
     * Features whose availability is gated by a hub **entitlement** when the support tier is enabled
     * (the [FeatureEntitlementGate.gatedFeatures] set). With no tier present (OSS) they fall through to
     * their plain toggle — feedback is freely usable, backups/compatibility-check are simply off by default.
     */
    val SUPPORT_TIER: Set<FeatureKey> = setOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_COMPATIBILITY_CHECK)

    /**
     * Support features that are **inert without a live hub** (unlike feedback, which is freely usable
     * locally). Their toggle default follows `epistola.support.enabled`: on with the tier, off in OSS.
     * Resolved in [FeatureToggleService.defaultFor].
     */
    val HUB_ONLY: Set<FeatureKey> = setOf(SUPPORT_BACKUPS, SUPPORT_COMPATIBILITY_CHECK)

    /**
     * Release maturity of a feature. [STABLE] shows no marker; [BETA]/[ALPHA] render a badge in the
     * nav, on the feature page, and in the admin Features list. The [label] is the user-facing text
     * (null for stable, so the UI renders nothing).
     */
    enum class FeatureStage(val label: String?) {
        STABLE(null),
        BETA("Beta"),
        ALPHA("Alpha"),
    }

    /** Display metadata for a feature, shown wherever the feature surfaces in the UI. */
    data class FeatureMetadata(
        val title: String,
        val description: String,
        val stage: FeatureStage = FeatureStage.STABLE,
    )

    val metadata: Map<FeatureKey, FeatureMetadata> = mapOf(
        SUPPORT_FEEDBACK to FeatureMetadata(
            "Feedback",
            "Enables the Support → Feedback feature (sync to epistola-hub).",
        ),
        SUPPORT_BACKUPS to FeatureMetadata(
            "Backups",
            "Enables the Support → Backups feature (faithful full-fidelity tenant backups + restore).",
            stage = FeatureStage.BETA,
        ),
        SUPPORT_COMPATIBILITY_CHECK to FeatureMetadata(
            "Upgrading",
            "Enables the Support → Upgrading feature (compatibility checks against upcoming Epistola releases). " +
                "Ships its own catalog-export snapshots to the hub for the checks.",
        ),
        STENCIL_PARAMETERS to FeatureMetadata("Stencil parameters", "Enables typed parameters on stencils."),
    )

    fun metadataFor(key: FeatureKey): FeatureMetadata? = metadata[key]

    fun stageOf(key: FeatureKey): FeatureStage = metadata[key]?.stage ?: FeatureStage.STABLE
}
