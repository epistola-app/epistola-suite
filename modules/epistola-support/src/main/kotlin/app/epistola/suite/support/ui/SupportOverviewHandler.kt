// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.GetTenantSupportSummary
import app.epistola.suite.support.connection.TenantSupportConnection
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Support → Overview for a tenant: whether the installation is connected to Epistola support, and
 * the tenant's own support settings pages — the other items of the Support nav group. Installation
 * details (claim code, last error) are on `/platform/support`, for installation administrators.
 * It is the one place a tenant sets what it shares with Epistola ([SupportSharingHandler]): a setup
 * wizard while nothing is set up, the installation preset or its own choice otherwise. Reads stored
 * state only; the page never calls the support service.
 */
@Component
class SupportOverviewHandler(private val references: TenantReferences, private val sharing: SupportSharingHandler) {
    fun overview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val summary = GetTenantSupportSummary(tenantId.key).query()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok().page("support/overview") {
            "pageTitle" to "Support - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "summary" to summary
            // A confidential tenant names itself to Epistola support by its reference; the connection off, it has nothing to name.
            "tenantReference" to references.takeIf { summary.connection != TenantSupportConnection.OFF && it.confidential() }?.reference(tenantId.key)
            sharing.overview(tenantId.key).let { overview ->
                "sharing" to overview
                "wizard" to overview?.takeIf { it.offersSetup }?.let { sharing.startWizard(it) }
            }
            "connectionSharing" to sharing.connectionSharing(tenantId.key)
            "epistolaOff" to (summary.connection == TenantSupportConnection.OFF)
        }
    }
}
