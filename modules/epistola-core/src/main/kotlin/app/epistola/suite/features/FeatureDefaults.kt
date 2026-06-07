package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.springframework.boot.context.properties.ConfigurationProperties

// TODO: If feature count grows beyond ~5, refactor to Map<String, Boolean> config property instead of per-feature constructor params + when expression
@ConfigurationProperties(prefix = "epistola.features")
data class FeatureDefaults(
    val supportFeedback: Boolean = false,
    val supportBackups: Boolean = false,
    val supportUpgrading: Boolean = false,
    val stencilParameters: Boolean = true,
) {
    fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
        KnownFeatures.SUPPORT_FEEDBACK -> supportFeedback
        KnownFeatures.SUPPORT_BACKUPS -> supportBackups
        KnownFeatures.SUPPORT_UPGRADING -> supportUpgrading
        KnownFeatures.STENCIL_PARAMETERS -> stencilParameters
        else -> false
    }
}
