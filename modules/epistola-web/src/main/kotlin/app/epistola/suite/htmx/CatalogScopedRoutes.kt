// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.queries.FindCatalogsByKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctionDsl
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router
import java.util.Optional

/** The path variable a catalog's namespaced address names its namespace by. */
const val CATALOG_NAMESPACE_VARIABLE: String = "catalogNamespace"

/** The request attribute an action reached by key alone carries the namespace that key resolved to in. */
const val CATALOG_NAMESPACE_ATTRIBUTE: String = "app.epistola.catalogNamespace"

/**
 * A catalog's pages and actions, routed relative to `/tenants/{tenantId}/catalogs`: each route starts
 * `/{catalogId}/…`. [CatalogScopedRouting] serves them at the catalog's address,
 * `/tenants/{tenantId}/catalogs/{catalogNamespace}/{catalogId}/…` (ADR 0034, section 9), and, while the
 * key names one catalog of the tenant, by key alone: a page redirects to the address, an action is
 * served as at the address ([CATALOG_NAMESPACE_ATTRIBUTE]).
 */
class CatalogScopedRoutes(val routes: RouterFunction<ServerResponse>)

/** [CatalogScopedRoutes] from a router whose routes start `/{catalogId}/…`. */
fun catalogScopedRoutes(routes: RouterFunctionDsl.() -> Unit): CatalogScopedRoutes = CatalogScopedRoutes(router(routes))

/**
 * Serves every [CatalogScopedRoutes] by address and by key alone.
 *
 * A path `/tenants/{t}/catalogs/{a}/{b}/…` is an address when the tenant has a catalog of namespace
 * `a` and key `b`, and by key alone (`a` the key) otherwise, so a key that is also the name of a
 * resource list (`templates`, `themes`, …) is never taken for one. Both come after every other router:
 * a catalog key may be any slug, so neither must take a tenant-level route. A router of tenant-level
 * routes under `/tenants/{tenantId}/catalogs` (`/registry/releases`, `/exchange/search`) says so with
 * an `@Order` below `LOWEST_PRECEDENCE - 1`.
 */
@Configuration
class CatalogScopedRouting {
    /** Both, as one router: by address, then by key alone, before every router without an order. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    fun catalogScopedRouter(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = catalogRoutesByAddress(scoped).and(catalogRoutesByKey(scoped))

    fun catalogRoutesByAddress(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = RouterFunctions.nest(RequestPredicate(::isAddress).and(RequestPredicates.path("/tenants/{tenantId}/catalogs/{$CATALOG_NAMESPACE_VARIABLE}")), all(scoped))

    fun catalogRoutesByKey(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = RouterFunctions.nest(RequestPredicate { !isAddress(it) }.and(RequestPredicates.path("/tenants/{tenantId}/catalogs")), all(scoped)).filter(::byKey)

    /** Every catalog's routes as one router; one that routes nothing in an application without catalog pages. */
    private fun all(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = scoped.map { it.routes }.reduceOrNull { a, b -> a.and(b) } ?: RouterFunction { Optional.empty() }

    companion object {
        /** Whether [request]'s path names a catalog by its address: a catalog of the tenant with that namespace and key. */
        fun isAddress(request: ServerRequest): Boolean {
            val (tenant, namespace, key) = addressParts(request.path()) ?: return false
            if (!CatalogNamespaces.isValid(namespace)) return false
            val catalogKey = CatalogKey.validateOrNull(key.substringBefore('@')) ?: return false
            val tenantKey = TenantKey.validateOrNull(tenant) ?: return false
            return runCatching { FindCatalogsByKey(tenantKey, catalogKey).query().catalogs.any { it.namespace == namespace } }.getOrDefault(false)
        }

        /** `{tenant}`, `{a}` and `{b}` of `/tenants/{tenant}/catalogs/{a}/{b}…`, or null for a shorter path. */
        private fun addressParts(path: String): Triple<String, String, String>? {
            // Decoded as path variables are: an editor encodes a draft's `@` (`letters%40<draft>`).
            val parts = path.trim('/').split('/').map { org.springframework.web.util.UriUtils.decode(it, Charsets.UTF_8) }
            if (parts.size < 5 || parts[0] != "tenants" || parts[2] != "catalogs") return null
            return Triple(parts[1], parts[3], parts[4])
        }

        /**
         * A route reached by key alone: the tenant's one catalog with that key, whose page it
         * redirects to by address and whose action it serves as at the address; a key that names no
         * catalog, or several, names nothing here.
         */
        fun byKey(request: ServerRequest, next: HandlerFunction<ServerResponse>): ServerResponse {
            val raw = request.pathVariables()["catalogId"] ?: return next.handle(request)
            val key = CatalogKey.validateOrNull(raw.substringBefore('@')) ?: return next.handle(request)
            val tenantKey = request.pathVariables()["tenantId"]?.let { TenantKey.validateOrNull(it) } ?: return next.handle(request)
            val found = FindCatalogsByKey(tenantKey, key).query().catalogs
            val catalog = found.singleOrNull() ?: return if (found.isEmpty()) {
                ServerResponse.notFound().build()
            } else {
                ServerResponse.badRequest().contentType(MediaType.TEXT_PLAIN)
                    .body("The catalog key ${key.value} names several catalogs of this tenant (${found.joinToString { it.address }}): name its namespace.")
            }
            if (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD) {
                val prefix = "/tenants/${tenantKey.value}/catalogs/"
                val path = request.path()
                val location = prefix + catalog.namespace + "/" + path.removePrefix(prefix) +
                    (request.servletRequest().queryString?.let { "?$it" } ?: "")
                return ServerResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, request.servletRequest().contextPath + location).build()
            }
            request.attributes()[CATALOG_NAMESPACE_ATTRIBUTE] = catalog.namespace
            return next.handle(request)
        }
    }
}
