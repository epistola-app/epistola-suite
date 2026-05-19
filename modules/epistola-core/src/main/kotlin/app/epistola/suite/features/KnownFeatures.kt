package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val FEEDBACK = FeatureKey.of("feedback")
    val STENCIL_PARAMETERS = FeatureKey.of("stencil-parameters")

    val all: List<FeatureKey> = listOf(FEEDBACK, STENCIL_PARAMETERS)

    val descriptions: Map<FeatureKey, String> = mapOf(
        FEEDBACK to "Enables feedback option.",
        STENCIL_PARAMETERS to "Enables typed parameters on stencils.",
    )
}
