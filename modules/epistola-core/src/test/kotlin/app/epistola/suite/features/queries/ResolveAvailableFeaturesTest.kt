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

    // A feature outside the entitlement gate's gated set: its availability is its plain toggle,
    // untouched by entitlement. Synthetic because every current known feature is support-tier.
    private val ungated = FeatureKey.of("ungated-feature")

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
        withToggles(KnownFeatures.SUPPORT_BACKUPS to true, ungated to true)
        given(gateProvider.ifAvailable).willReturn(gate(entitled = true))

        val available = handler.handle(ResolveAvailableFeatures(tenant))

        assertThat(available[KnownFeatures.SUPPORT_BACKUPS]).isTrue()
        assertThat(available[ungated]).isTrue()
    }

    @Test
    fun `support-tier feature is unavailable when the tier is on but not entitled`() {
        withToggles(KnownFeatures.SUPPORT_BACKUPS to true)
        given(gateProvider.ifAvailable).willReturn(gate(entitled = false))

        assertThat(handler.handle(ResolveAvailableFeatures(tenant))[KnownFeatures.SUPPORT_BACKUPS]).isFalse()
    }

    @Test
    fun `with no tier present features fall through to their plain toggle`() {
        // OSS / support tier off: no gate present, so availability is the plain toggle map. Hub-only
        // features are off by default there (FeatureToggleService.defaultFor), but an explicit toggle
        // still shows them — entitlement only applies once the tier (gate) is present.
        withToggles(KnownFeatures.SUPPORT_BACKUPS to true, ungated to true)
        given(gateProvider.ifAvailable).willReturn(null)

        val available = handler.handle(ResolveAvailableFeatures(tenant))

        assertThat(available[KnownFeatures.SUPPORT_BACKUPS]).isTrue()
        assertThat(available[ungated]).isTrue()
    }
}
