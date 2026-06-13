package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Global default state for non-support feature toggles. The **support-tier** features
 * ([KnownFeatures.SUPPORT_TIER]) are not configured here — their default follows
 * `epistola.support.enabled` (on with the tier, off otherwise), resolved in [FeatureToggleService].
 */
@ConfigurationProperties(prefix = "epistola.features")
data class FeatureDefaults(
    val stencilParameters: Boolean = true,
) {
    fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
        KnownFeatures.STENCIL_PARAMETERS -> stencilParameters
        else -> false
    }
}
