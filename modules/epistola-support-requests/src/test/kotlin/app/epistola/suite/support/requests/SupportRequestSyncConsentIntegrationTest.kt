// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.support.connection.EnsureConfiguredSupportConnection
import app.epistola.suite.support.requests.commands.CreateSupportRequest
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingSettings
import app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.IsSupportRequestSyncConsentInForce
import app.epistola.suite.support.requests.consent.SupportRequestSharingConnection
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestPrincipalUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The per-tenant consent to send feedback to Epistola support: its commands, queries and settings read model. */
class SupportRequestSyncConsentIntegrationTest : IntegrationTestBase() {

    @Test
    fun `a tenant has no consent until it is given`() {
        val tenant = createTenant("Consent None")

        val consent = withMediator { GetSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }

        assertThat(consent.inForce).isFalse()
        assertThat(consent.consentedAt).isNull()
        assertThat(withMediator { IsSupportRequestSyncConsentInForce(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }).isFalse()
    }

    @Test
    fun `consent is given, kept when given again, withdrawn and given again`() {
        val tenant = createTenant("Consent Cycle")

        val given = withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(given.inForce).isTrue()
        assertThat(given.consentedAt).isNotNull()
        assertThat(given.consentedBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        assertThat(withMediator { IsSupportRequestSyncConsentInForce(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }).isTrue()

        val again = withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(again.consentedAt).`as`("giving it again changes nothing").isEqualTo(given.consentedAt)

        val withdrawal = withMediator { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(withdrawal.consent.inForce).isFalse()
        assertThat(withdrawal.consent.withdrawnAt).isNotNull()
        assertThat(withdrawal.consent.withdrawnBy).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
        assertThat(withdrawal.stoppedSupportRequest).isZero()
        assertThat(withMediator { IsSupportRequestSyncConsentInForce(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }).isFalse()

        val regiven = withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        assertThat(regiven.inForce).isTrue()
        assertThat(regiven.withdrawnAt).isNull()
    }

    @Test
    fun `consent is one tenant's only`() {
        val consenting = createTenant("Consent Mine")
        val other = createTenant("Consent Other")

        withMediator { GrantSupportRequestSyncConsent(consenting.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }

        assertThat(withMediator { IsSupportRequestSyncConsentInForce(other.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }).isFalse()
    }

    @Test
    fun `with the support connection off, support request stays local even with consent`() {
        val tenant = createTenant("Consent Off")
        withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val author = UserKey.of("00000000-0000-0000-0000-feedbac00091")
        ensureUser(author, "consent-author", "consent-author@epistola.test", "Consent Author")

        val supportRequest = withMediator {
            CreateSupportRequest(
                id = SupportRequestId(SupportRequestKey.generate(), TenantId(tenant.id)),
                title = "Local",
                description = "desc",
                category = SupportRequestCategory.QUESTION,
                priority = SupportRequestPriority.LOW,
                sourceUrl = null,
                consoleLogs = null,
                metadata = null,
                createdBy = author,
            ).execute()
        }

        assertThat(supportRequest.syncStatus).isEqualTo(SyncStatus.NOT_CONFIGURED)
    }

    @Test
    fun `the settings say the connection is off and offer no consent, but always offer withdrawal`() {
        val tenant = createTenant("Consent Settings")

        val before = withMediator { GetSupportRequestSharingSettings(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(before.connection).isEqualTo(SupportRequestSharingConnection.OFF)
        assertThat(before.canGrant).isFalse()
        assertThat(before.canWithdraw).isFalse()
        assertThat(before.sendsSupportRequest).isFalse()

        withMediator { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() }
        val after = withMediator { GetSupportRequestSharingSettings(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }
        assertThat(after.canWithdraw).isTrue()
        assertThat(after.consentedButWaiting).isTrue()
        assertThat(after.sendsSupportRequest).isFalse()
    }

    @Test
    fun `only who manages the tenant's integrations may give or read consent`() {
        val tenant = createTenant("Consent Denied")
        val viewer = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-feedbac00092"),
            externalId = "consent-viewer",
            email = "consent-viewer@epistola.test",
            displayName = "Consent Viewer",
            tenantMemberships = mapOf(tenant.id to setOf(TenantRole.CONTENT_VIEWER)),
            currentTenantId = tenant.id,
        )

        assertThatThrownBy { runAs(viewer) { GrantSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { runAs(viewer) { WithdrawSupportRequestSyncConsent(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).execute() } }.isInstanceOf(PermissionDeniedException::class.java)
        assertThatThrownBy { runAs(viewer) { GetSupportRequestSharingSettings(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() } }.isInstanceOf(PermissionDeniedException::class.java)
        assertThat(withMediator { IsSupportRequestSyncConsentInForce(tenant.id, connectionId = withMediator { EnsureConfiguredSupportConnection().execute() }).query() }).isFalse()
    }
}
