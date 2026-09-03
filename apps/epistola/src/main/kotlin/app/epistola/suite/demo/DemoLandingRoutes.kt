// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.htmx.redirect
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.TenantHandler
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * Demo-only: sends a signed-in user straight to their own tenant instead of the tenant picker.
 *
 * Everywhere else `GET /` lists the tenants you belong to and you pick one — right for a customer
 * who works across several. A demo visitor has exactly one that is theirs (plus read/write on the
 * shared `demo` tenant, see [DemoLoginMembershipResolver]), so making them choose is a click
 * between them and the product.
 *
 * Registered at [Ordered.HIGHEST_PRECEDENCE] so it wins the `GET /` that
 * [app.epistola.suite.tenants.TenantRoutes] also declares — `RouterFunctionMapping` sorts router
 * beans and takes the first match. When there is no personal tenant to send them to it delegates to
 * the normal handler rather than inventing a different empty state, so nothing is lost.
 *
 * Gated on `epistola.demo.enabled` like the rest of this package, which means local development
 * gets it too. (The shared secret is the one thing here gated on the profile instead — it is a
 * security control, and `local` sets this property.)
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["epistola.demo.enabled"], havingValue = "true")
class DemoLandingRoutes(private val tenantHandler: TenantHandler) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun demoLandingRouterFunction(): RouterFunction<ServerResponse> = router {
        GET("/", ::landing)
    }

    private fun landing(request: ServerRequest): ServerResponse {
        val principal = SecurityContext.current()
        val personal = DemoLoginMembershipResolver.deriveTenantKeyFromEmail(principal.email)

        // Only redirect somewhere they can actually get into. A principal whose memberships came
        // from the identity provider rather than from the demo resolver has no personal tenant, and
        // one whose tenant creation failed at login would land on a 404.
        if (personal == null || !principal.tenantMemberships.containsKey(personal)) {
            log.debug("Demo mode: {} has no personal tenant; showing the tenant list", principal.email)
            return tenantHandler.list(request)
        }

        return redirect("/tenants/${personal.value}")
    }
}
