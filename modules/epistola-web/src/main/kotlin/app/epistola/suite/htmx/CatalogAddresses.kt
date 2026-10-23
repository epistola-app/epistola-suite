// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.suite.catalog.CatalogNames
import org.springframework.context.annotation.Configuration
import org.thymeleaf.context.IExpressionContext
import org.thymeleaf.linkbuilder.StandardLinkBuilder
import org.thymeleaf.spring6.SpringTemplateEngine

/**
 * The addresses a page links a catalog by (ADR 0034, phase 4). A request reached at a catalog's
 * namespaced address, `/tenants/{t}/catalogs/{namespace}/{key}/…` ([CatalogScopedRouting]), links that
 * catalog the same way: every address of it by key alone that the request builds (a template's `@{…}`
 * link, a redirect, an HTMX header) is built namespaced. Elsewhere a catalog is linked by key alone,
 * which names it while it is the tenant's one catalog with that key; pages that list catalogs link a
 * catalog whose key is shared by its namespaced address themselves.
 *
 * What a request names it learns in its [CatalogNames.frame] (bound per request by the app's
 * `MediatorFilter`), so the names hold while its page renders too.
 */
object CatalogAddresses {
    /** [url] with its address of a catalog by key alone written namespaced, for a key [names] gives a namespace. */
    fun address(url: String, names: Map<String, String> = CatalogNames.current()): String {
        if (names.isEmpty()) return url
        val match = BY_KEY.find(url) ?: return url
        val key = match.groupValues[2]
        val namespace = names[key] ?: return url
        return url.replaceRange(match.groups[2]!!.range, "$namespace/$key")
    }

    /**
     * [path] with its namespaced address of a catalog that [names] give that namespace written by key
     * alone: the inverse of [address], for code that reads a page's path by its parts (the catalog
     * segment fifth) and builds a link from it, which [address] then names again.
     */
    fun byKey(path: String, names: Map<String, String> = CatalogNames.current()): String {
        if (names.isEmpty()) return path
        val match = NAMESPACED.find(path) ?: return path
        val (namespace, key) = match.groupValues[2] to match.groupValues[3]
        if (names[key] != namespace) return path
        return path.replaceRange(match.groups[2]!!.range.first, match.groups[3]!!.range.first, "")
    }

    /** `/tenants/{t}/catalogs/{namespace}/{key}` followed by a path, a draft, or nothing. */
    private val NAMESPACED = Regex("(/tenants/[^/?#]+/catalogs/)([^/@?#]+)/([^/@?#]+)(?=[/@?#]|$)")

    /** `/tenants/{t}/catalogs/{key}` followed by a path or a draft: a catalog's page or action. */
    private val BY_KEY = Regex("(/tenants/[^/?#]+/catalogs/)([^/@?#]+)(?=[/@])")
}

/** Builds every `@{…}` link of a page as [CatalogAddresses] says, so a namespaced page links namespaced. */
class CatalogAddressLinkBuilder : StandardLinkBuilder() {
    override fun processLink(context: IExpressionContext, link: String): String = super.processLink(context, CatalogAddresses.address(link))
}

/** Installs [CatalogAddressLinkBuilder] on the template engine. */
@Configuration
class CatalogAddressLinkConfig(templateEngine: SpringTemplateEngine) {
    init {
        templateEngine.setLinkBuilder(CatalogAddressLinkBuilder())
    }
}
