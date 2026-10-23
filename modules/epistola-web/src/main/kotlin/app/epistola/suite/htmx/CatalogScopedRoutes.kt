// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.common.ids.CatalogKey
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.servlet.function.HandlerFunction
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

/**
 * A catalog's pages and actions, routed relative to `/tenants/{tenantId}/catalogs`: each route starts
 * `/{catalogId}/…`. [CatalogScopedRouting] serves them at two addresses (ADR 0034, phase 4):
 *
 * - `/tenants/{tenantId}/catalogs/{catalogId}/…`, by key alone, while the key names one catalog of the
 *   tenant (as it always has), and
 * - `/tenants/{tenantId}/catalogs/{catalogNamespace}/{catalogId}/…`, by namespace and key, which names
 *   one of two catalogs that share a key. The key then means the catalog of that namespace for the
 *   whole request ([CatalogNames]), and [catalogSegment] carries the namespace, so every link built
 *   from it names the catalog the same way ([CatalogAddresses]).
 */
class CatalogScopedRoutes(val routes: RouterFunction<ServerResponse>)

/** [CatalogScopedRoutes] from a router whose routes start `/{catalogId}/…`. */
fun catalogScopedRoutes(routes: RouterFunctionDsl.() -> Unit): CatalogScopedRoutes = CatalogScopedRoutes(router(routes))

/**
 * Serves every [CatalogScopedRoutes] by key and by namespace and key.
 *
 * By key first, and both after every other router: a catalog key may be any slug, so a key-alone
 * address must not take a tenant-level route, nor a namespaced address a key-alone one. A router of
 * tenant-level routes under `/tenants/{tenantId}/catalogs` (`/registry/releases`, `/exchange/search`)
 * says so with an `@Order` below `LOWEST_PRECEDENCE - 1`: one without an order comes after the key-alone routes. What remains is a namespaced address whose key is also the name of a resource list
 * (`templates`, `themes`, …): that one is taken by key, as a resource of the catalog named by the
 * namespace, which has no such resource.
 */
@Configuration
class CatalogScopedRouting {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    fun catalogRoutesByKey(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = RouterFunctions.nest(RequestPredicates.path("/tenants/{tenantId}/catalogs"), all(scoped))

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun catalogRoutesByNamespace(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = RouterFunctions.nest(RequestPredicates.path("/tenants/{tenantId}/catalogs/{$CATALOG_NAMESPACE_VARIABLE}"), all(scoped)).filter(::named)

    /** Every catalog's routes as one router; one that routes nothing in an application without catalog pages. */
    private fun all(scoped: List<CatalogScopedRoutes>): RouterFunction<ServerResponse> = scoped.map { it.routes }.reduceOrNull { a, b -> a.and(b) } ?: RouterFunction { Optional.empty() }

    companion object {
        /**
         * Runs [next] with the request's catalog key meaning the catalog of the namespace its address
         * names, for the handler ([CatalogNames]) and for the links its page renders ([CatalogAddresses]).
         * An address naming no valid namespace names no catalog; one naming no valid key is left to the
         * handler, which refuses it.
         */
        fun named(request: ServerRequest, next: HandlerFunction<ServerResponse>): ServerResponse {
            val namespace = request.pathVariables()[CATALOG_NAMESPACE_VARIABLE]?.takeIf { CatalogNamespaces.isValid(it) }
                ?: return ServerResponse.notFound().build()
            val key = request.pathVariables()["catalogId"]?.substringBefore('@')?.let { CatalogKey.validateOrNull(it) }
                ?: return next.handle(request)
            // Learned for the whole request, its rendering included; given to the handler either way.
            CatalogNames.name(key, namespace)
            return CatalogNames.within(key, namespace) { next.handle(request) }
        }
    }
}
