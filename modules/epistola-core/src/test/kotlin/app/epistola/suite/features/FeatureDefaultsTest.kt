package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureDefaultsTest {

    @Test
    fun `isEnabled returns configured default for known feature`() {
        val defaults = FeatureDefaults(feedback = true)
        assertTrue(defaults.isEnabled(KnownFeatures.FEEDBACK))
    }

    @Test
    fun `isEnabled returns false when feature is disabled`() {
        val defaults = FeatureDefaults(feedback = false)
        assertFalse(defaults.isEnabled(KnownFeatures.FEEDBACK))
    }

    @Test
    fun `isEnabled returns false for unknown feature`() {
        val defaults = FeatureDefaults(feedback = true)
        assertFalse(defaults.isEnabled(FeatureKey.of("unknown-feature")))
    }

    @Test
    fun `default constructor has feedback disabled`() {
        val defaults = FeatureDefaults()
        assertFalse(defaults.isEnabled(KnownFeatures.FEEDBACK))
    }
}
