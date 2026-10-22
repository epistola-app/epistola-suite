// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.catalog.CatalogNames
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Gives every MCP request a [CatalogNames.frame], so a tool that is handed `namespace/key`
 * ([mcpCatalogKey]) names that catalog for the rest of its call (ADR 0034, phase 4).
 */
@Component
class McpCatalogNamesFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/mcp")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        CatalogNames.frame { filterChain.doFilter(request, response) }
    }
}
