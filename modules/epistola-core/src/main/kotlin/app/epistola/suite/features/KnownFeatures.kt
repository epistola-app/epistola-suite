package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val SUPPORT_FEEDBACK = FeatureKey.of("support-feedback")
    val STENCIL_PARAMETERS = FeatureKey.of("stencil-parameters")

    val all: List<FeatureKey> = listOf(SUPPORT_FEEDBACK, STENCIL_PARAMETERS)

    val descriptions: Map<FeatureKey, String> = mapOf(
        SUPPORT_FEEDBACK to "Enables the Support → Feedback feature (sync to epistola-hub).",
        STENCIL_PARAMETERS to "Enables typed parameters on stencils.",
    )
}
