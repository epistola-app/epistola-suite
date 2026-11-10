// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror.ui

import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.nav.NavContributor
import app.epistola.suite.htmx.nav.NavItem
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.mirror.CatalogMirrorConsentOutdatedException
import app.epistola.suite.support.mirror.CatalogMirrorNotOptedInException
import app.epistola.suite.support.mirror.CatalogMirrorPolicy
import app.epistola.suite.support.mirror.GetCatalogMirrorSettings
import app.epistola.suite.support.mirror.OptInCatalogMirror
import app.epistola.suite.support.mirror.OptOutCatalogMirror
import app.epistola.suite.support.mirror.RequestCatalogMirrorSync
import app.epistola.suite.support.mirror.SetCatalogMirrorPolicy
import app.epistola.suite.support.ui.SupportNavContributor
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.util.UUID

/**
 * Adds "Catalog mirror" to the Support group for tenant administrators while the support
 * connection is on: with it off there is nothing to share with.
 */
@Component
class CatalogMirrorNavContributor(
    private val supportProperties: SupportProperties,
) : NavContributor {
    override fun items(context: UiRequestContext): List<NavItem> {
        if (!supportProperties.enabled || !context.hasPermission(Permission.INTEGRATIONS_MANAGE)) return emptyList()
        return listOf(NavItem(SupportNavContributor.GROUP_KEY, SECTION, "Catalog mirror", "support/mirror", order = 20))
    }

    companion object {
        const val SECTION = "mirror"
    }
}

@Configuration
class CatalogMirrorRoutes(private val handler: CatalogMirrorHandler) {
    @Bean
    fun catalogMirrorRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/support/mirror".nest {
            GET("", handler::show)
            POST("/policy", handler::setPolicy)
            POST("/sync", handler::sync)
        }
    }
}

/**
 * `/tenants/{t}/support/mirror` — what this tenant shares with the Epistola catalog mirror. Every
 * action swaps the `#catalog-mirror` panel in place, or redirects back to the page without HTMX.
 * The mediator enforces the permissions; the page offers only what the viewer may do.
 */
@Component
class CatalogMirrorHandler(private val registry: SupportConnectionRegistry) {
    fun show(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val tenant = GetTenant(tenantId.key).query() ?: return ServerResponse.notFound().build()
        return ServerResponse.ok().page("support/mirror/page") {
            "pageTitle" to "Catalog mirror - Epistola"
            "tenant" to tenant
            "tenantId" to tenantId.key
            "settings" to settings(request)
            "notice" to null
        }
    }

    fun setPolicy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val namespace = request.param("namespace").orElse("")
        val key = request.param("key").orElse("")
        val policy = request.param("policy").map { runCatching { CatalogMirrorPolicy.valueOf(it) }.getOrNull() }.orElse(null)
        val catalog = CatalogKey.validateOrNull(key)?.takeIf { namespace.isNotBlank() }?.let { CatalogId(namespace, it, TenantId(tenantId.key)) }
        val notice = if (catalog == null || policy == null) {
            "Choose a catalog and a policy."
        } else {
            try {
                SetCatalogMirrorPolicy(catalog, policy, connection(request)).execute()
                "Saved the policy of $namespace/$key."
            } catch (e: CatalogNotFoundException) {
                e.message
            }
        }
        return panel(request, notice)
    }

    fun sync(request: ServerRequest): ServerResponse {
        val notice = try {
            RequestCatalogMirrorSync(request.tenantId().key, connection(request)).execute()
            "Sync requested. It runs in the background; reload to see how it went."
        } catch (e: CatalogMirrorNotOptedInException) {
            e.message
        }
        return panel(request, notice)
    }

    private fun settings(request: ServerRequest) = GetCatalogMirrorSettings(
        tenantKey = request.tenantId().key,
        offset = request.param("offset").map { it.toIntOrNull() ?: 0 }.orElse(0).coerceAtLeast(0),
        connectionId = connection(request),
    ).query()

    /**
     * The connection the page is about (`connection`, from the query or the form); when none or not a
     * UUID, the first that selects the mirror.
     */
    private fun connection(request: ServerRequest): UUID = request.param("connection").map { runCatching { UUID.fromString(it) }.getOrNull() }.orElse(null)
        ?: registry.all().firstOrNull { it.isSelected(SupportScope.MIRROR) }?.id
        ?: SupportConnectionStore.NO_CONNECTION

    private fun panel(request: ServerRequest, notice: String?): ServerResponse {
        val tenantId = request.tenantId()
        val settings = settings(request)
        return request.htmx {
            fragment("support/mirror/page", "mirror") {
                "tenantId" to tenantId.key
                "settings" to settings
                "notice" to notice
            }
            onFullPage { redirect("/tenants/${tenantId.key.value}/support/mirror?connection=${settings.connectionId}") }
        }
    }
}
