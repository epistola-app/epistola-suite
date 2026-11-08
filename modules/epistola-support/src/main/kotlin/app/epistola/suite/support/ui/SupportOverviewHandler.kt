// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.support.connection.GetTenantSupportSummary
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Support → Overview for a tenant: whether the installation is connected to Epistola support, and
 * the tenant's own support settings pages — the other items of the Support nav group. Installation
 * details (claim code, last error) are on `/platform/support`, for installation administrators.
 * Reads stored state only; the page never calls the support service.
 */
@Component
class SupportOverviewHandler {
    fun overview(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val summary = GetTenantSupportSummary(tenantId.key).query()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()

        return ServerResponse.ok().page("support/overview") {
            "pageTitle" to "Support - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "summary" to summary
        }
    }
}
