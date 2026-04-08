package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "epistola.features")
data class FeatureDefaults(
    val feedback: Boolean = false,
) {
    fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
        KnownFeatures.FEEDBACK -> feedback
        else -> false
    }
}
