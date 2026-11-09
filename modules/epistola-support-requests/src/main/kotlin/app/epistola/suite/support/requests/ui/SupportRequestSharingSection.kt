// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.SupportConnectionRegistry
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

/**
 * Sharing support requests as a step of the tenant's setup and as part of the installation preset,
 * per support connection (ADR 0053): each connection has its own consent, default and restriction.
 */
@Component
class SupportRequestSharingSection(private val registry: SupportConnectionRegistry) : TenantSharingSection {
    override val key = REQUESTS_SECTION
    override val order = 10

    /** Asked only once the installation selected support requests for that connection. */
    override fun offered(connectionId: UUID): Boolean = registry.find(connectionId)?.isSelected(SupportScope.REQUESTS) == true
    override val title = "Support requests"
    override val summary = "Support requests, with their comments, screenshot and logs, can go to this support connection, who can reply."
    override val statementFragment = "support-requests/sharing :: statement"
    override val choices = listOf(
        SharingChoice(SHARE, "Share support requests", shares = true),
        SharingChoice(NO, "Keep support requests in this installation", shares = false),
    )

    override fun tenantView(tenantKey: TenantKey, connectionId: UUID): TenantSharingView {
        val consent = GetSupportRequestSyncConsent(tenantKey, connectionId).query()
        return TenantSharingView(
            section = this,
            value = if (consent.inForce) SHARE else NO,
            followsPreset = consent.followsDefault,
            decidedBy = if (consent.inForce) consent.consentedBy else consent.withdrawnBy,
            decidedAt = if (consent.inForce) consent.consentedAt else consent.withdrawnAt,
        )
    }

    override fun installationView(connectionId: UUID): InstallationSharingView {
        val default = GetSupportRequestSharingDefault(connectionId).query()
        return InstallationSharingView(this, default.isSet, if (default.share) SHARE else NO, default.fromConfiguration, default.setBy, default.setAt)
    }

    override fun applyOwn(tenantKey: TenantKey, value: String, connectionId: UUID) {
        when (value) {
            SHARE -> GrantSupportRequestSyncConsent(tenantKey, connectionId).execute()
            NO -> WithdrawSupportRequestSyncConsent(tenantKey, connectionId).execute()
            else -> throw IllegalArgumentException("Unknown support request choice '$value'")
        }
    }

    override fun followPreset(tenantKey: TenantKey, connectionId: UUID) {
        FollowSupportRequestSharingDefault(tenantKey, connectionId).execute()
    }

    override fun forgetAll(connectionId: UUID) {
        ForgetSupportRequestSharing(connectionId).execute()
    }

    override fun saveInstallation(form: Map<String, List<String>>, connectionId: UUID): String? {
        val current = GetSupportRequestSharingDefault(connectionId).query()
        val share = when (form.field("requests.default")) {
            SHARE -> true

            NO -> false

            NO_PRESET -> {
                if (!current.isSet) return null
                ClearSupportRequestSharingDefault(connectionId).execute()
                return "Support requests: no preset; each tenant sets it up."
            }

            else -> return null
        }
        if (current.isSet && !current.fromConfiguration && current.share == share) return null
        SetSupportRequestSharingDefault(share, connectionId).execute()
        return if (share) "Support requests: shared." else "Support requests: kept in each installation."
    }

    companion object {
        const val SHARE = "SHARE"
        const val NO = "NO"
    }
}
