package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val SUPPORT_FEEDBACK = FeatureKey.of("support-feedback")
    val SUPPORT_BACKUPS = FeatureKey.of("support-backups")
    val SUPPORT_UPGRADING = FeatureKey.of("support-upgrading")
    val STENCIL_PARAMETERS = FeatureKey.of("stencil-parameters")

    val all: List<FeatureKey> = listOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_UPGRADING, STENCIL_PARAMETERS)

    /**
     * Features whose availability is gated by a hub **entitlement** when the support tier is enabled
     * (the [FeatureEntitlementGate.gatedFeatures] set). With no tier present (OSS) they fall through to
     * their plain toggle — feedback is freely usable, backups/upgrading are simply off by default.
     */
    val SUPPORT_TIER: Set<FeatureKey> = setOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_UPGRADING)

    /**
     * Support features that are **inert without a live hub** (unlike feedback, which is freely usable
     * locally). Their toggle default follows `epistola.support.enabled`: on with the tier, off in OSS.
     * Resolved in [FeatureToggleService.defaultFor].
     */
    val HUB_ONLY: Set<FeatureKey> = setOf(SUPPORT_BACKUPS, SUPPORT_UPGRADING)

    val descriptions: Map<FeatureKey, String> = mapOf(
        SUPPORT_FEEDBACK to "Enables the Support → Feedback feature (sync to epistola-hub).",
        SUPPORT_BACKUPS to "Enables the Support → Backups feature (faithful full-fidelity tenant backups + restore).",
        SUPPORT_UPGRADING to "Enables the Support → Upgrading feature (compatibility checks against upcoming Epistola releases). Ships its own catalog-export snapshots to the hub for the checks.",
        STENCIL_PARAMETERS to "Enables typed parameters on stencils.",
    )
}
