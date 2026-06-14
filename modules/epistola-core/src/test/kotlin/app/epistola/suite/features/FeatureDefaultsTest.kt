package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureDefaultsTest {
    @Test
    fun `stencil-parameters defaults to enabled`() {
        assertTrue(FeatureDefaults().isEnabled(KnownFeatures.STENCIL_PARAMETERS))
    }

    @Test
    fun `stencil-parameters returns configured value when disabled`() {
        assertFalse(FeatureDefaults(stencilParameters = false).isEnabled(KnownFeatures.STENCIL_PARAMETERS))
    }

    @Test
    fun `unknown feature is disabled`() {
        assertFalse(FeatureDefaults().isEnabled(FeatureKey.of("unknown-feature")))
    }

    @Test
    fun `feedback is freely usable and defaults on here`() {
        assertTrue(FeatureDefaults().isEnabled(KnownFeatures.SUPPORT_FEEDBACK))
    }

    @Test
    fun `hub-only features are not resolved here (their default follows the support tier)`() {
        // FeatureDefaults intentionally does not configure hub-only support features; FeatureToggleService
        // derives their default from epistola.support.enabled. isEnabled treats them as unknown → false.
        val defaults = FeatureDefaults()
        assertFalse(defaults.isEnabled(KnownFeatures.SUPPORT_BACKUPS))
        assertFalse(defaults.isEnabled(KnownFeatures.SUPPORT_UPGRADING))
    }
}
