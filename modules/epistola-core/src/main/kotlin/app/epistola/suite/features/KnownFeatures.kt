package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val FEEDBACK = FeatureKey.of("feedback")

    val all: List<FeatureKey> = listOf(FEEDBACK)

    val descriptions: Map<FeatureKey, String> = mapOf(
        FEEDBACK to "Enables feedback option.",
    )
}
