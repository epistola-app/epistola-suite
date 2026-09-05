// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.identity.canonical
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.htmx.catalogIdOrNull
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.net.URI

/**
 * Route filters that send a `GET` at an address a resource has moved away from to the address it
 * occupies now, so links and bookmarks from before a relocation keep working. Everything beneath
 * the resource -- variants, versions, contract -- redirects with it, since those are path segments
 * under its address. Other methods pass through: a page reached through the redirect only ever
 * posts to canonical URLs.
 */
object CanonicalAddressRedirects {
    fun template(request: ServerRequest, next: (ServerRequest) -> ServerResponse): ServerResponse {
        if (request.method() != HttpMethod.GET || "id" !in request.pathVariables()) return next(request)
        val requested = request.templateId(request.tenantId()) ?: return next(request)
        val canonical = requested.canonical()
        if (canonical == requested) return next(request)
        return redirect(
            request,
            from = "/templates/${requested.catalogKey.value}/${requested.key.value}",
            to = "/templates/${canonical.catalogKey.value}/${canonical.key.value}",
        )
    }

    fun stencil(request: ServerRequest, next: (ServerRequest) -> ServerResponse): ServerResponse {
        if (request.method() != HttpMethod.GET || "stencilId" !in request.pathVariables()) return next(request)
        val catalog = request.catalogIdOrNull() ?: return next(request)
        val key = runCatching { StencilKey.of(request.pathVariable("stencilId")) }.getOrNull() ?: return next(request)
        val requested = StencilId(key, CatalogId(catalog, request.tenantId()))
        val canonical = requested.canonical()
        if (canonical == requested) return next(request)
        return redirect(
            request,
            from = "/stencils/${requested.catalogKey.value}/${requested.key.value}",
            to = "/stencils/${canonical.catalogKey.value}/${canonical.key.value}",
        )
    }

    private fun redirect(request: ServerRequest, from: String, to: String): ServerResponse {
        val query = request.uri().rawQuery?.let { "?$it" } ?: ""
        return ServerResponse.seeOther(URI.create(request.path().replaceFirst(from, to) + query)).build()
    }
}
