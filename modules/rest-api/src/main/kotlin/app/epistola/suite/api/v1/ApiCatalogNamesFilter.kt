// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.common.ids.CatalogKey
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * A REST path names its catalog in full, `…/{registry}/{namespace}/{catalogKey}/…`; the key means the
 * catalog of that namespace for the whole request ([CatalogNames]), so two catalogs with one key are
 * each reached by their own path (ADR 0034, phase 4).
 */
@Component
class ApiCatalogNamesFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.removePrefix(request.contextPath).startsWith("/api/tenants/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        val match = CATALOG.find(path) ?: DEPLOYMENT.find(path)
        val key = match?.groupValues?.get(2)?.let(CatalogKey::validateOrNull)
        if (match == null || key == null) {
            filterChain.doFilter(request, response)
            return
        }
        CatalogNames.within(key, match.groupValues[1]) { filterChain.doFilter(request, response) }
    }

    private companion object {
        val CATALOG = Regex("^/api/tenants/[^/]+/catalogs/[^/]+/([^/]+)/([^/]+)(/|$)")
        val DEPLOYMENT = Regex("^/api/tenants/[^/]+/environments/[^/]+/deployments/[^/]+/([^/]+)/([^/]+)(/|$)")
    }
}
