package app.epistola.suite.features

import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * The global default for a feature with no tenant override: hub-only support features follow
 * `epistola.support.enabled`, everything else follows [FeatureDefaults]. `defaultFor` does not touch
 * the database, so this is a pure unit test (the JDBI dependency is an unused mock).
 */
class FeatureToggleDefaultsTest {
    private fun service(supportEnabled: Boolean) = FeatureToggleService(mock(Jdbi::class.java), FeatureDefaults(), supportEnabled)

    @Test
    fun `hub-only features default to the support tier`() {
        val on = service(supportEnabled = true)
        val off = service(supportEnabled = false)
        for (feature in KnownFeatures.HUB_ONLY) {
            assertThat(on.defaultFor(feature)).`as`("$feature with support on").isTrue()
            assertThat(off.defaultFor(feature)).`as`("$feature with support off").isFalse()
        }
    }

    @Test
    fun `feedback is freely usable and defaults on regardless of the support tier`() {
        assertThat(service(supportEnabled = false).defaultFor(KnownFeatures.SUPPORT_FEEDBACK)).isTrue()
        assertThat(service(supportEnabled = true).defaultFor(KnownFeatures.SUPPORT_FEEDBACK)).isTrue()
    }

    @Test
    fun `non-support features follow FeatureDefaults regardless of the support tier`() {
        assertThat(service(supportEnabled = false).defaultFor(KnownFeatures.STENCIL_PARAMETERS)).isTrue()
        assertThat(service(supportEnabled = true).defaultFor(KnownFeatures.STENCIL_PARAMETERS)).isTrue()
    }
}
