package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Global default state for feature toggles with no tenant override. The **hub-only** support
 * features ([KnownFeatures.HUB_ONLY] — backups/upgrading) are not configured here; their default
 * follows `epistola.support.enabled` (resolved in [FeatureToggleService]). Feedback, by contrast, is
 * freely usable locally, so it defaults **on** here regardless of the support tier.
 */
@ConfigurationProperties(prefix = "epistola.features")
data class FeatureDefaults(
    val supportFeedback: Boolean = true,
) {
    fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
        KnownFeatures.SUPPORT_FEEDBACK -> supportFeedback
        else -> false
    }
}
