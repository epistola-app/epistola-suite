package app.epistola.suite.features.queries

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureEntitlementGate
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider

class ResolveAvailableFeaturesTest {
    private val tenant = TenantKey.of("acme")
    private val toggleService = mock(FeatureToggleService::class.java)

    @Suppress("UNCHECKED_CAST")
    private val gateProvider = mock(ObjectProvider::class.java) as ObjectProvider<FeatureEntitlementGate>

    private val handler = ResolveAvailableFeaturesHandler(toggleService, gateProvider)

    private fun withToggles(vararg toggles: Pair<FeatureKey, Boolean>) {
        given(toggleService.resolveAll(tenant)).willReturn(mapOf(*toggles))
    }

    private fun gate(entitled: Boolean): FeatureEntitlementGate = object : FeatureEntitlementGate {
        override val gatedFeatures = KnownFeatures.SUPPORT_TIER

        override fun isEntitled(
            featureKey: FeatureKey,
            tenantKey: TenantKey,
        ) = entitled
    }

    @Test
    fun `support-tier feature is available when the tier is on and entitled`() {
        withToggles(KnownFeatures.SUPPORT_BACKUPS to true, KnownFeatures.STENCIL_PARAMETERS to true)
        given(gateProvider.ifAvailable).willReturn(gate(entitled = true))

        val available = handler.handle(ResolveAvailableFeatures(tenant))

        assertThat(available[KnownFeatures.SUPPORT_BACKUPS]).isTrue()
        assertThat(available[KnownFeatures.STENCIL_PARAMETERS]).isTrue()
    }

    @Test
    fun `support-tier feature is unavailable when the tier is on but not entitled`() {
        withToggles(KnownFeatures.SUPPORT_BACKUPS to true)
        given(gateProvider.ifAvailable).willReturn(gate(entitled = false))

        assertThat(handler.handle(ResolveAvailableFeatures(tenant))[KnownFeatures.SUPPORT_BACKUPS]).isFalse()
    }

    @Test
    fun `support-tier feature is unavailable when the tier is off, even with the toggle on`() {
        // OSS / support tier off: no gate present. The guard hides support-tier features regardless.
        withToggles(KnownFeatures.SUPPORT_BACKUPS to true, KnownFeatures.STENCIL_PARAMETERS to true)
        given(gateProvider.ifAvailable).willReturn(null)

        val available = handler.handle(ResolveAvailableFeatures(tenant))

        assertThat(available[KnownFeatures.SUPPORT_BACKUPS]).isFalse()
        // non-tier features still follow the plain toggle
        assertThat(available[KnownFeatures.STENCIL_PARAMETERS]).isTrue()
    }
}
