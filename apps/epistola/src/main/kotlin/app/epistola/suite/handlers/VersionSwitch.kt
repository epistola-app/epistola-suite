// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogSegment

/**
 * Switching catalog versions keeps the page: the page someone was on, in the version they switch to.
 * The version menu's links stay the same on every page (so the context bar is not sent again with
 * each navigation, docs/workspace-shell.md), and the page comes along when one is clicked.
 */
object VersionSwitch {

    /**
     * [from] (a path with its query) in [target]'s version, or null when it is not a page of this
     * catalog in tenant [tenantKey], so the switch goes to the version's overview instead. Only a
     * path of this tenant is ever returned: the switch cannot be made to lead anywhere else.
     */
    fun samePage(from: String, tenantKey: String, target: CatalogSegment): String? {
        val tenantPrefix = "/tenants/$tenantKey/"
        if (!from.startsWith(tenantPrefix) || "//" in from || "\\" in from) return null
        val path = from.substringBefore('?')
        val query = from.substringAfter('?', missingDelimiterValue = "")
        val key = target.key.value
        // "", "tenants", tenant, "catalogs", namespace/catalog, … (ADR 0034, section 9)
        val parts = catalogPathParts(path)
        fun isThisCatalog(segment: String) = segment.substringBefore('@').let { it == target.address || it == key }
        val address = target.at(null).value

        if (parts.getOrNull(3) == "catalogs") {
            val segment = parts.getOrNull(4) ?: return null
            if (!isThisCatalog(segment)) return null
            return when (parts.getOrNull(5)) {
                // A version's overview has its own address: the next version's, or the work's.
                null, "browse", "draft" -> if (target.draft == null) "/tenants/$tenantKey/catalogs/$address/browse" else "/tenants/$tenantKey/catalogs/${target.value}/draft"

                // The same for every version.
                "versions", "releases", "deployments" -> from

                else -> {
                    parts[4] = target.value
                    parts.joinToString("/") + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                }
            }
        }
        // Every page of a catalog is under `/catalogs/{catalog}/`; anything else is not one.
        return null
    }
}

/**
 * [path] split at `/`, a catalog's address after `catalogs` (`{namespace}/{key}[@draft]`, ADR 0034,
 * section 9) kept as one part, so the catalog is part 4: "", "tenants", tenant, "catalogs", catalog, ….
 */
internal fun catalogPathParts(path: String): MutableList<String> {
    val parts = path.split('/').toMutableList()
    if (parts.getOrNull(3) == "catalogs" && parts.size > 5) {
        parts[4] = parts[4] + "/" + parts[5]
        parts.removeAt(5)
    }
    return parts
}
