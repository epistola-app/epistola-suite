// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.namespaces.CatalogKeyNamespaces
import app.epistola.suite.catalog.namespaces.ListCatalogKeyNamespaces
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

/**
 * A catalog's UI address names its namespace: `/tenants/{tenant}/catalogs/{namespace}/{key}/…`
 * (ADR 0034, phase 4: within a tenant a catalog is unique by namespace and key, so two catalogs
 * called `letters` live side by side).
 *
 * - **A namespaced address** is served by the route of the key alone (the request continues as if it
 *   named the key alone), with the key meaning the
 *   catalog of that namespace for the whole request ([CatalogNames]); every address of that catalog in
 *   what it answers (HTML, JSON, redirects, HTMX headers) is written namespaced again, so the pages
 *   and everything they call keep naming it.
 * - **An address by key alone** keeps working while the key names one catalog, as it always has; the
 *   pages link to a catalog by key unless its key is shared, as the catalog list does. When the key
 *   names several, it is refused where it is looked up, naming them.
 *
 * Routes keep their `{catalogId}` path variable, so no handler or template changes.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class CatalogAddressFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !path(request).startsWith("/tenants/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val path = path(request)
        NAMESPACED.matchEntire(path)?.let { match ->
            val (tenant, namespace, segment, rest) = match.destructured
            val key = segment.substringBefore('@')
            if (CatalogNamespaces.isValid(namespace) && namespace in namespacesOf(tenant, key)) {
                serveNamespaced(request, response, filterChain, tenant, namespace, key, "/tenants/$tenant/catalogs/$segment$rest")
                return
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun serveNamespaced(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain, tenant: String, namespace: String, key: String, target: String) {
        val rewrite = addressRewriter(request.contextPath, tenant, namespace, key)
        val wrapped = RewritingResponse(response, rewrite)
        val byKey = object : HttpServletRequestWrapper(request) {
            override fun getRequestURI(): String = request.contextPath + target
            override fun getServletPath(): String = target
            override fun getPathInfo(): String? = null
            override fun getRequestURL(): StringBuffer = StringBuffer(request.requestURL.toString().replace(request.requestURI, requestURI))
        }
        CatalogNames.within(CatalogKey.of(key), namespace) { chain.doFilter(byKey, wrapped) }
        val type = wrapped.contentType.orEmpty()
        if (type.contains("html") || type.contains("json")) {
            val body = String(wrapped.contentAsByteArray, Charsets.UTF_8)
            wrapped.resetBuffer()
            wrapped.outputStream.write(rewrite(body).toByteArray(Charsets.UTF_8))
        }
        wrapped.copyBodyToResponse()
    }

    /** The namespaces in which [tenant] has a catalog with [key]; none when that cannot be asked. */
    private fun namespacesOf(tenant: String, key: String): List<String> = try {
        val catalogKey = CatalogKey.validateOrNull(key) ?: return emptyList()
        val tenantKey = TenantKey.validateOrNull(tenant) ?: return emptyList()
        ListCatalogKeyNamespaces(tenantKey, catalogKey).query().namespaces
    } catch (e: RuntimeException) {
        // Not allowed, or no such tenant: the address is served as it is, and refused there.
        log.debug("Catalog namespaces of {}/{} not read: {}", tenant, key, e.message)
        CatalogKeyNamespaces(emptyList()).namespaces
    }

    private fun path(request: HttpServletRequest) = request.requestURI.removePrefix(request.contextPath)

    private class RewritingResponse(response: HttpServletResponse, private val rewrite: (String) -> String) : ContentCachingResponseWrapper(response) {
        override fun setHeader(name: String, value: String) = super.setHeader(name, rewrite(value))

        override fun addHeader(name: String, value: String) = super.addHeader(name, rewrite(value))

        override fun sendRedirect(location: String) = super.sendRedirect(rewrite(location))
    }

    companion object {
        private val NAMESPACED = Regex("^/tenants/([^/]+)/catalogs/([^/]+)/([^/]+)(/.*)?$")

        /** Writes every by-key address of the catalog [key] of [tenant] as its namespaced one. */
        fun addressRewriter(contextPath: String, tenant: String, namespace: String, key: String): (String) -> String {
            val byKey = Regex("${Regex.escape("$contextPath/tenants/$tenant/catalogs/")}${Regex.escape(key)}(?=[/@\"'?#&<\\s]|$)")
            val replacement = "$contextPath/tenants/$tenant/catalogs/$namespace/$key"
            return { text -> byKey.replace(text) { replacement } }
        }
    }
}
