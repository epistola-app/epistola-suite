// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.requests.consent.ClearSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.FollowSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.ForgetSupportRequestSharing
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.GetSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_SECTION
import app.epistola.suite.support.requests.consent.SetSupportRequestSharingDefault
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.support.sharing.InstallationSharingView
import app.epistola.suite.support.sharing.NO_PRESET
import app.epistola.suite.support.sharing.SharingChoice
import app.epistola.suite.support.sharing.TenantSharingSection
import app.epistola.suite.support.sharing.TenantSharingView
import app.epistola.suite.support.sharing.field
import org.springframework.stereotype.Component
import java.util.UUID

/** Feedback sharing as a step of the tenant's setup and as part of the installation preset. */
@Component
class SupportRequestSharingSection(private val connection: SupportConnection) : TenantSharingSection {
    override val key = REQUESTS_SECTION
    override val order = 10

    /** Asked only once the installation selected support requests under Services. */
    override fun offered(connectionId: UUID): Boolean = connection.isSelected(SupportScope.REQUESTS)
    override val title = "Support requests"
    override val summary = "Each new support request, with its comments, screenshot and logs, goes to Epistola support, who can reply."
    override val statementFragment = "support-requests/sharing :: statement"
    override val choices = listOf(
        SharingChoice(SHARE, "Share support requests with Epistola support", shares = true),
        SharingChoice(NO, "Keep support requests in this installation", shares = false),
    )

    override fun tenantView(tenantKey: TenantKey, connectionId: UUID): TenantSharingView {
        onlySupportRequestConnection(connectionId)
        val consent = GetSupportRequestSyncConsent(tenantKey).query()
        return TenantSharingView(
            section = this,
            value = if (consent.inForce) SHARE else NO,
            followsPreset = consent.followsDefault,
            decidedBy = if (consent.inForce) consent.consentedBy else consent.withdrawnBy,
            decidedAt = if (consent.inForce) consent.consentedAt else consent.withdrawnAt,
        )
    }

    override fun installationView(connectionId: UUID): InstallationSharingView {
        onlySupportRequestConnection(connectionId)
        val default = GetSupportRequestSharingDefault().query()
        return InstallationSharingView(this, default.isSet, if (default.share) SHARE else NO, default.fromConfiguration, default.setBy, default.setAt)
    }

    override fun applyOwn(tenantKey: TenantKey, value: String, connectionId: UUID) {
        onlySupportRequestConnection(connectionId)
        when (value) {
            SHARE -> GrantSupportRequestSyncConsent(tenantKey).execute()
            NO -> WithdrawSupportRequestSyncConsent(tenantKey).execute()
            else -> throw IllegalArgumentException("Unknown support request choice '$value'")
        }
    }

    override fun followPreset(tenantKey: TenantKey, connectionId: UUID) {
        onlySupportRequestConnection(connectionId)
        FollowSupportRequestSharingDefault(tenantKey).execute()
    }

    override fun forgetAll(connectionId: UUID) {
        onlySupportRequestConnection(connectionId)
        ForgetSupportRequestSharing().execute()
    }

    override fun saveInstallation(form: Map<String, List<String>>, connectionId: UUID): String? {
        onlySupportRequestConnection(connectionId)
        val current = GetSupportRequestSharingDefault().query()
        val share = when (form.field("requests.default")) {
            SHARE -> true

            NO -> false

            NO_PRESET -> {
                if (!current.isSet) return null
                ClearSupportRequestSharingDefault().execute()
                return "Support requests: no preset; each tenant sets it up."
            }

            else -> return null
        }
        if (current.isSet && !current.fromConfiguration && current.share == share) return null
        SetSupportRequestSharingDefault(share).execute()
        return if (share) "Support requests: shared with Epistola support." else "Support requests: kept in each installation."
    }

    companion object {
        const val SHARE = "SHARE"
        const val NO = "NO"
    }

    /** Feedback goes over one connection until support requests have a delivery per connection (ADR 0053, rollout step 2). */
    private fun onlySupportRequestConnection(connectionId: UUID) {
        check(connectionId == REQUESTS_CONNECTION) { "Support requests are shared over connection $REQUESTS_CONNECTION only, not $connectionId" }
    }
}
