package app.epistola.suite.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SupportEntitlementResolutionTest {
    private val now = Instant.parse("2026-06-08T00:00:00Z")

    private fun entry(
        feature: String = "support-backups",
        tenant: String? = null,
        effect: EntitlementEffect = EntitlementEffect.ALLOW,
        expiresAt: Instant? = null,
    ) = StoredEntitlement(feature, tenant, effect, expiresAt)

    @Test
    fun `no entries resolves to NOT_CONFIGURED`() {
        assertThat(resolveEntitlement(emptyList(), "support-backups", "acme", now))
            .isEqualTo(EntitlementDecision.NOT_CONFIGURED)
    }

    @Test
    fun `installation-wide ALLOW applies to any tenant`() {
        val entries = listOf(entry())
        assertThat(resolveEntitlement(entries, "support-backups", "acme", now)).isEqualTo(EntitlementDecision.ALLOWED)
        assertThat(resolveEntitlement(entries, "support-backups", "globex", now)).isEqualTo(EntitlementDecision.ALLOWED)
    }

    @Test
    fun `tenant-scoped ALLOW applies only to that tenant`() {
        val entries = listOf(entry(tenant = "acme"))
        assertThat(resolveEntitlement(entries, "support-backups", "acme", now)).isEqualTo(EntitlementDecision.ALLOWED)
        assertThat(resolveEntitlement(entries, "support-backups", "globex", now)).isEqualTo(EntitlementDecision.NOT_CONFIGURED)
    }

    @Test
    fun `tenant-scoped DENY overrides an installation-wide ALLOW for that tenant only`() {
        val entries = listOf(entry(), entry(tenant = "acme", effect = EntitlementEffect.DENY))
        assertThat(resolveEntitlement(entries, "support-backups", "acme", now)).isEqualTo(EntitlementDecision.DENIED)
        assertThat(resolveEntitlement(entries, "support-backups", "globex", now)).isEqualTo(EntitlementDecision.ALLOWED)
    }

    @Test
    fun `DENY beats ALLOW within the same scope`() {
        val entries = listOf(entry(), entry(effect = EntitlementEffect.DENY))
        assertThat(resolveEntitlement(entries, "support-backups", "acme", now)).isEqualTo(EntitlementDecision.DENIED)
    }

    @Test
    fun `expired entries are ignored`() {
        val entries = listOf(entry(expiresAt = now.minusSeconds(1)))
        assertThat(resolveEntitlement(entries, "support-backups", "acme", now)).isEqualTo(EntitlementDecision.NOT_CONFIGURED)
    }

    @Test
    fun `a different feature does not match`() {
        val entries = listOf(entry(feature = "support-upgrading"))
        assertThat(resolveEntitlement(entries, "support-backups", "acme", now)).isEqualTo(EntitlementDecision.NOT_CONFIGURED)
    }

    @Test
    fun `installation-wide entitlement requires an installation-wide ALLOW`() {
        assertThat(resolveInstallationEntitlement(emptyList(), "support-telemetry", now)).isFalse()
        assertThat(resolveInstallationEntitlement(listOf(entry(feature = "support-telemetry")), "support-telemetry", now)).isTrue()
    }

    @Test
    fun `a tenant-scoped ALLOW alone does not grant installation-wide entitlement`() {
        val entries = listOf(entry(feature = "support-telemetry", tenant = "acme"))
        assertThat(resolveInstallationEntitlement(entries, "support-telemetry", now)).isFalse()
    }

    @Test
    fun `an installation-wide DENY withholds installation-wide entitlement`() {
        val entries = listOf(entry(feature = "support-telemetry"), entry(feature = "support-telemetry", effect = EntitlementEffect.DENY))
        assertThat(resolveInstallationEntitlement(entries, "support-telemetry", now)).isFalse()
    }

    @Test
    fun `a tenant-scoped DENY does not affect installation-wide entitlement`() {
        val entries =
            listOf(entry(feature = "support-telemetry"), entry(feature = "support-telemetry", tenant = "acme", effect = EntitlementEffect.DENY))
        assertThat(resolveInstallationEntitlement(entries, "support-telemetry", now)).isTrue()
    }

    @Test
    fun `an expired installation-wide ALLOW does not grant entitlement`() {
        val entries = listOf(entry(feature = "support-telemetry", expiresAt = now.minusSeconds(1)))
        assertThat(resolveInstallationEntitlement(entries, "support-telemetry", now)).isFalse()
    }
}
