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
    /**
     * Quality checks. Like feedback (and unlike backups/upgrading) this is **not** hub-gated — the
     * ledger, its in-process sources, and the report all work with the support tier off — so its
     * default lives here rather than following `epistola.support.enabled`. Off while the feature is
     * ALPHA; stated explicitly rather than riding the `else` branch below, so enabling it later is a
     * deliberate edit and not a silent change of meaning.
     */
    val quality: Boolean = false,
    /**
     * AI chat in the template editor. This is an editor-only alpha surface for now, using the same
     * toggle-only model as quality: no support-tier entitlement and no hub-only default. Off by
     * default until the assistant surface is production-ready.
     */
    val aiChat: Boolean = false,
) {
    fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
        KnownFeatures.SUPPORT_FEEDBACK -> supportFeedback
        KnownFeatures.QUALITY -> quality
        KnownFeatures.AI_CHAT -> aiChat
        else -> false
    }
}
