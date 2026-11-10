// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.requests.consent.ApplyConfiguredSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.ClearSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.FollowSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.ForgetSupportRequestSharing
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingSettings
import app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.SetSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestPrincipalUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired

/**
 * The installation's default for feedback sharing: it applies directly to every tenant that made no
 * choice of its own, recorded as consent given by the installation administrator who set it, and a
 * tenant's own choice always wins until it follows the default again.
 */
@Isolated("The default applies to every tenant in the database")
class SupportRequestSharingDefaultIntegrationTest : IntegrationTestBase() {
    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var supportConnections: app.epistola.suite.support.connection.SupportConnections

    @AfterEach
    fun resetDefault() {
        withMediator { ClearSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        // Which configured preset was applied is bookkeeping no command writes or clears.
        jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key LIKE :key").bind("key", "${ApplyConfiguredSupportRequestSharingDefault.APPLIED_KEY}%").execute() }
    }

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `the default shares for every tenant without a choice of its own, as given by who set it`() {
        val following = createTenant("Default Following")
        val saidNo = createTenant("Default Said No")
        withMediator { WithdrawSupportRequestSyncConsent(saidNo.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val saidYes = createTenant("Default Said Yes")
        withMediator { GrantSupportRequestSyncConsent(saidYes.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val followed = withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(followed.inForce).isTrue()
        assertThat(followed.followsDefault).isTrue()
        assertThat(followed.consentedBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        val no = withMediator { GetSupportRequestSyncConsent(saidNo.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(no.inForce).`as`("a tenant's own no wins").isFalse()
        assertThat(no.followsDefault).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(saidYes.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.followsDefault).isFalse()
        val default = withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(default.share).isTrue()
        assertThat(default.setBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        assertThat(withMediator { GetSupportRequestSharingSettings(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.installationDefault.share).isTrue()
    }

    @Test
    fun `disconnecting forgets every tenant's choice and the preset, so each is asked again`() {
        val own = createTenant("Forget Own")
        withMediator { GrantSupportRequestSyncConsent(own.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val saidNo = createTenant("Forget No")
        withMediator { WithdrawSupportRequestSyncConsent(saidNo.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val following = createTenant("Forget Following")
        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val forgotten = withMediator { ForgetSupportRequestSharing(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        assertThat(forgotten.tenants).isGreaterThanOrEqualTo(3)
        listOf(own, saidNo, following).forEach { tenant ->
            val consent = withMediator { GetSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
            assertThat(consent.inForce).`as`(tenant.name).isFalse()
            assertThat(consent.consentedAt).`as`("no choice left for ${tenant.name}").isNull()
            assertThat(consent.withdrawnAt).isNull()
        }
        assertThat(withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).isFalse()
    }

    @Test
    fun `a tenant created later follows the default at once`() {
        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val created = createTenant("Default Created Later")

        val consent = withMediator { GetSupportRequestSyncConsent(created.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(consent.inForce).isTrue()
        assertThat(consent.followsDefault).isTrue()
    }

    @Test
    fun `turning the default off withdraws only where the tenant follows it`() {
        val following = createTenant("Default Off Following")
        val own = createTenant("Default Off Own")
        withMediator { GrantSupportRequestSyncConsent(own.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        withMediator { SetSupportRequestSharingDefault(share = false, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val followed = withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(followed.inForce).isFalse()
        assertThat(followed.followsDefault).isTrue()
        assertThat(withMediator { GetSupportRequestSyncConsent(own.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).`as`("its own consent stays").isTrue()
    }

    @Test
    fun `a tenant's own choice overrides the default until it follows the default again`() {
        val tenant = createTenant("Default Override")
        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { SetSupportRequestSharingDefault(share = false, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(withMediator { GetSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).isFalse()

        val followed = withMediator { FollowSupportRequestSharingDefault(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        assertThat(followed.inForce).isTrue()
        assertThat(followed.followsDefault).isTrue()
    }

    @Test
    fun `clearing the preset stops what it shared and leaves those tenants to set themselves up`() {
        val following = createTenant("Default Cleared Following")
        val own = createTenant("Default Cleared Own")
        withMediator { GrantSupportRequestSyncConsent(own.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        withMediator { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        withMediator { ClearSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        val followed = withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(followed.inForce).isFalse()
        assertThat(followed.followsDefault).isTrue()
        assertThat(withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(own.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).isTrue()
    }

    @Test
    fun `the configured preset applies at startup only while none is stored, as given by the configuration`() {
        val following = createTenant("Default Configured")
        val configured = ApplyConfiguredSupportRequestSharingDefault(jdbi, SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(requests = "share")), supportConnections)

        configured.apply()

        val default = withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(default.share).isTrue()
        assertThat(default.fromConfiguration).isTrue()
        val consent = withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(consent.inForce).isTrue()
        assertThat(consent.consentedBy).`as`("given by the configuration, not a person").isNull()

        withMediator { SetSupportRequestSharingDefault(share = false, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        configured.apply()
        assertThat(withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.share).`as`("a saved preset wins").isFalse()
    }

    @Test
    fun `a configured preset an administrator cleared is not applied again at the next start`() {
        val following = createTenant("Default Configured Cleared")
        val configuration = SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(requests = "share"))
        ApplyConfiguredSupportRequestSharingDefault(jdbi, configuration, supportConnections).apply()
        withMediator { ClearSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        // The next start, with the same configuration.
        ApplyConfiguredSupportRequestSharingDefault(jdbi, configuration, supportConnections).apply()

        assertThat(withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.isSet).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).isFalse()
    }

    @Test
    fun `a configured preset that changes takes effect at the next start, while nobody saved one`() {
        val following = createTenant("Default Configured Changed")
        ApplyConfiguredSupportRequestSharingDefault(jdbi, SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(requests = "share")), supportConnections).apply()
        assertThat(withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).isTrue()

        // The operator turns sharing off in the configuration and restarts.
        ApplyConfiguredSupportRequestSharingDefault(jdbi, SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(requests = "no")), supportConnections).apply()

        assertThat(withMediator { GetSupportRequestSharingDefault(connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.share).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(following.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }.inForce).`as`("its following tenants stop sharing").isFalse()
    }

    @Test
    fun `only an installation administrator sets the default`() {
        val tenant = createTenant("Default Denied")
        val tenantAdmin = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-feedbac00093"),
            externalId = "default-tenant-admin",
            email = "default-tenant-admin@epistola.test",
            displayName = "Tenant Admin",
            tenantMemberships = mapOf(tenant.id to setOf(TenantRole.TENANT_ADMINISTRATOR)),
            currentTenantId = tenant.id,
        )

        assertThatThrownBy { runAs(tenantAdmin) { SetSupportRequestSharingDefault(share = true, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }.isInstanceOf(PlatformAccessDeniedException::class.java)
        assertThat(runAs(tenantAdmin) { FollowSupportRequestSharingDefault(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }.followsDefault).isTrue()
    }
}
