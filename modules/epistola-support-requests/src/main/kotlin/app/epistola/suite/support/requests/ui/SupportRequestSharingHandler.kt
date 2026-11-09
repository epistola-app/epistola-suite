// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.consent.GetSupportRequestSharingSettings
import app.epistola.suite.support.requests.consent.GrantSupportRequestSyncConsent
import app.epistola.suite.support.requests.consent.WithdrawSupportRequestSyncConsent
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * `/tenants/{t}/support/request-sharing` — what of the tenant's feedback goes to Epistola support, to whom,
 * and the tenant's consent. Read-only: the tenant sets it on its Support page, the one place for what
 * it shares with Epistola. The mediator enforces `INTEGRATIONS_MANAGE`.
 */
@Configuration
class SupportRequestSharingRoutes(private val handler: SupportRequestSharingHandler) {
    @Bean
    fun supportRequestSharingRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/support/request-sharing".nest {
            GET("", handler::show)
        }
    }
}

@Component
class SupportRequestSharingHandler {
    fun show(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val settings = GetSupportRequestSharingSettings(tenantId.key).query()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        return ServerResponse.ok().page("support-requests/sharing") {
            "pageTitle" to "Support request sharing - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "settings" to settings
            "activeNavSection" to SupportRequestNavContributor.SHARING_SECTION
        }
    }
}
