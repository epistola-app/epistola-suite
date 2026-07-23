package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureDefaultsTest {
    @Test
    fun `unknown feature is disabled`() {
        assertFalse(FeatureDefaults().isEnabled(FeatureKey.of("unknown-feature")))
    }

    @Test
    fun `feedback is freely usable and defaults on here`() {
        assertTrue(FeatureDefaults().isEnabled(KnownFeatures.SUPPORT_FEEDBACK))
    }

    @Test
    fun `alpha editor features default off here`() {
        val defaults = FeatureDefaults()
        assertFalse(defaults.isEnabled(KnownFeatures.QUALITY))
        assertFalse(defaults.isEnabled(KnownFeatures.AI_CHAT))
    }

    @Test
    fun `hub-only features are not resolved here (their default follows the support tier)`() {
        // FeatureDefaults intentionally does not configure hub-only support features; FeatureToggleService
        // derives their default from epistola.support.enabled. isEnabled treats them as unknown → false.
        val defaults = FeatureDefaults()
        assertFalse(defaults.isEnabled(KnownFeatures.SUPPORT_BACKUPS))
        assertFalse(defaults.isEnabled(KnownFeatures.SUPPORT_COMPATIBILITY_CHECK))
    }
}
