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

    @AfterEach
    fun resetDefault() {
        withMediator { ClearSupportRequestSharingDefault().execute() }
        // Which configured preset was applied is bookkeeping no command writes or clears.
        jdbi.useHandle<Exception> { it.createUpdate("DELETE FROM app_metadata WHERE key = :key").bind("key", ApplyConfiguredSupportRequestSharingDefault.APPLIED_KEY).execute() }
    }

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `the default shares for every tenant without a choice of its own, as given by who set it`() {
        val following = createTenant("Default Following")
        val saidNo = createTenant("Default Said No")
        withMediator { WithdrawSupportRequestSyncConsent(saidNo.id).execute() }
        val saidYes = createTenant("Default Said Yes")
        withMediator { GrantSupportRequestSyncConsent(saidYes.id).execute() }

        withMediator { SetSupportRequestSharingDefault(share = true).execute() }

        val followed = withMediator { GetSupportRequestSyncConsent(following.id).query() }
        assertThat(followed.inForce).isTrue()
        assertThat(followed.followsDefault).isTrue()
        assertThat(followed.consentedBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        val no = withMediator { GetSupportRequestSyncConsent(saidNo.id).query() }
        assertThat(no.inForce).`as`("a tenant's own no wins").isFalse()
        assertThat(no.followsDefault).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(saidYes.id).query() }.followsDefault).isFalse()
        val default = withMediator { GetSupportRequestSharingDefault().query() }
        assertThat(default.share).isTrue()
        assertThat(default.setBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        assertThat(withMediator { GetSupportRequestSharingSettings(following.id).query() }.installationDefault.share).isTrue()
    }

    @Test
    fun `disconnecting forgets every tenant's choice and the preset, so each is asked again`() {
        val own = createTenant("Forget Own")
        withMediator { GrantSupportRequestSyncConsent(own.id).execute() }
        val saidNo = createTenant("Forget No")
        withMediator { WithdrawSupportRequestSyncConsent(saidNo.id).execute() }
        val following = createTenant("Forget Following")
        withMediator { SetSupportRequestSharingDefault(share = true).execute() }

        val forgotten = withMediator { ForgetSupportRequestSharing().execute() }

        assertThat(forgotten.tenants).isGreaterThanOrEqualTo(3)
        listOf(own, saidNo, following).forEach { tenant ->
            val consent = withMediator { GetSupportRequestSyncConsent(tenant.id).query() }
            assertThat(consent.inForce).`as`(tenant.name).isFalse()
            assertThat(consent.consentedAt).`as`("no choice left for ${tenant.name}").isNull()
            assertThat(consent.withdrawnAt).isNull()
        }
        assertThat(withMediator { GetSupportRequestSharingDefault().query() }.isSet).isFalse()
    }

    @Test
    fun `a tenant created later follows the default at once`() {
        withMediator { SetSupportRequestSharingDefault(share = true).execute() }

        val created = createTenant("Default Created Later")

        val consent = withMediator { GetSupportRequestSyncConsent(created.id).query() }
        assertThat(consent.inForce).isTrue()
        assertThat(consent.followsDefault).isTrue()
    }

    @Test
    fun `turning the default off withdraws only where the tenant follows it`() {
        val following = createTenant("Default Off Following")
        val own = createTenant("Default Off Own")
        withMediator { GrantSupportRequestSyncConsent(own.id).execute() }
        withMediator { SetSupportRequestSharingDefault(share = true).execute() }

        withMediator { SetSupportRequestSharingDefault(share = false).execute() }

        val followed = withMediator { GetSupportRequestSyncConsent(following.id).query() }
        assertThat(followed.inForce).isFalse()
        assertThat(followed.followsDefault).isTrue()
        assertThat(withMediator { GetSupportRequestSyncConsent(own.id).query() }.inForce).`as`("its own consent stays").isTrue()
    }

    @Test
    fun `a tenant's own choice overrides the default until it follows the default again`() {
        val tenant = createTenant("Default Override")
        withMediator { SetSupportRequestSharingDefault(share = true).execute() }

        withMediator { WithdrawSupportRequestSyncConsent(tenant.id).execute() }
        withMediator { SetSupportRequestSharingDefault(share = false).execute() }
        withMediator { SetSupportRequestSharingDefault(share = true).execute() }
        assertThat(withMediator { GetSupportRequestSyncConsent(tenant.id).query() }.inForce).isFalse()

        val followed = withMediator { FollowSupportRequestSharingDefault(tenant.id).execute() }

        assertThat(followed.inForce).isTrue()
        assertThat(followed.followsDefault).isTrue()
    }

    @Test
    fun `clearing the preset stops what it shared and leaves those tenants to set themselves up`() {
        val following = createTenant("Default Cleared Following")
        val own = createTenant("Default Cleared Own")
        withMediator { GrantSupportRequestSyncConsent(own.id).execute() }
        withMediator { SetSupportRequestSharingDefault(share = true).execute() }

        withMediator { ClearSupportRequestSharingDefault().execute() }

        val followed = withMediator { GetSupportRequestSyncConsent(following.id).query() }
        assertThat(followed.inForce).isFalse()
        assertThat(followed.followsDefault).isTrue()
        assertThat(withMediator { GetSupportRequestSharingDefault().query() }.isSet).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(own.id).query() }.inForce).isTrue()
    }

    @Test
    fun `the configured preset applies at startup only while none is stored, as given by the configuration`() {
        val following = createTenant("Default Configured")
        val configured = ApplyConfiguredSupportRequestSharingDefault(jdbi, SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(requests = "share")))

        configured.apply()

        val default = withMediator { GetSupportRequestSharingDefault().query() }
        assertThat(default.share).isTrue()
        assertThat(default.fromConfiguration).isTrue()
        val consent = withMediator { GetSupportRequestSyncConsent(following.id).query() }
        assertThat(consent.inForce).isTrue()
        assertThat(consent.consentedBy).`as`("given by the configuration, not a person").isNull()

        withMediator { SetSupportRequestSharingDefault(share = false).execute() }
        configured.apply()
        assertThat(withMediator { GetSupportRequestSharingDefault().query() }.share).`as`("a saved preset wins").isFalse()
    }

    @Test
    fun `a configured preset an administrator cleared is not applied again at the next start`() {
        val following = createTenant("Default Configured Cleared")
        val configuration = SupportProperties(enabled = true, tenantDefaults = SupportProperties.TenantDefaults(requests = "share"))
        ApplyConfiguredSupportRequestSharingDefault(jdbi, configuration).apply()
        withMediator { ClearSupportRequestSharingDefault().execute() }

        // The next start, with the same configuration.
        ApplyConfiguredSupportRequestSharingDefault(jdbi, configuration).apply()

        assertThat(withMediator { GetSupportRequestSharingDefault().query() }.isSet).isFalse()
        assertThat(withMediator { GetSupportRequestSyncConsent(following.id).query() }.inForce).isFalse()
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

        assertThatThrownBy { runAs(tenantAdmin) { SetSupportRequestSharingDefault(share = true).execute() } }.isInstanceOf(PlatformAccessDeniedException::class.java)
        assertThat(runAs(tenantAdmin) { FollowSupportRequestSharingDefault(tenant.id).execute() }.followsDefault).isTrue()
    }
}
