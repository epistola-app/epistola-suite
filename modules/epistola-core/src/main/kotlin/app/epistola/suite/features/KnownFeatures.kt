package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val SUPPORT_FEEDBACK = FeatureKey.of("support-feedback")
    val SUPPORT_BACKUPS = FeatureKey.of("support-backups")
    val SUPPORT_UPGRADING = FeatureKey.of("support-upgrading")
    val STENCIL_PARAMETERS = FeatureKey.of("stencil-parameters")

    val all: List<FeatureKey> = listOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_UPGRADING, STENCIL_PARAMETERS)

    /**
     * Features that require the commercial support tier (a hub entitlement). They default **on** with
     * the tier but are **unavailable when the tier is off** (OSS), regardless of their toggle default.
     */
    val SUPPORT_TIER: Set<FeatureKey> = setOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_UPGRADING)

    val descriptions: Map<FeatureKey, String> = mapOf(
        SUPPORT_FEEDBACK to "Enables the Support → Feedback feature (sync to epistola-hub).",
        SUPPORT_BACKUPS to "Enables the Support → Backups feature (catalog snapshot backups + restore via epistola-hub).",
        SUPPORT_UPGRADING to "Enables the Support → Upgrading feature (compatibility checks against upcoming Epistola releases). Relies on the snapshots sent by Backups.",
        STENCIL_PARAMETERS to "Enables typed parameters on stencils.",
    )
}
