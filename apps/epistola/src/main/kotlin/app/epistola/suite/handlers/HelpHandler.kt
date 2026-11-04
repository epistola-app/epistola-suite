// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.requireTenantAccess
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Help: what Epistola is for, the words its screens use, and where each thing is, for someone who
 * has never seen it and for someone who uses it without knowing it well. Every member of the tenant
 * may read it; the places it names are links only for those who may open them (the template gates
 * each on `auth`), so the page reads the same for everyone and leads nobody to a refusal.
 */
@Component
class HelpHandler {

    fun page(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireTenantAccess(tenantId.key)
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        return ServerResponse.ok().page("help") {
            "pageTitle" to "Help - Epistola"
            "tenantId" to tenantId.key
            "tenant" to tenant
            // What the context bar calls this page, which no nav item names (WorkspaceBuilder).
            "workspaceSection" to "Help"
        }
    }
}
