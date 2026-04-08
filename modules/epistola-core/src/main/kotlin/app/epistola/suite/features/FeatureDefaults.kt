package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.springframework.boot.context.properties.ConfigurationProperties

// TODO: If feature count grows beyond ~5, refactor to Map<String, Boolean> config property instead of per-feature constructor params + when expression
@ConfigurationProperties(prefix = "epistola.features")
data class FeatureDefaults(
    val feedback: Boolean = false,
) {
    fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
        KnownFeatures.FEEDBACK -> feedback
        else -> false
    }
}
