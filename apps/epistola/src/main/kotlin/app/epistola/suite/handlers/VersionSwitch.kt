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
        val parts = path.split('/').toMutableList() // "", "tenants", tenant, area, catalog, …
        fun isThisCatalog(segment: String) = segment.substringBefore('@') == key

        if (parts.getOrNull(3) == "catalogs") {
            val segment = parts.getOrNull(4) ?: return null
            if (!isThisCatalog(segment)) return null
            return when (parts.getOrNull(5)) {
                // A version's overview has its own address: the next version's, or the work's.
                null, "browse", "draft" -> if (target.draft == null) "/tenants/$tenantKey/catalogs/$key/browse" else "/tenants/$tenantKey/catalogs/${target.value}/draft"

                // The same for every version.
                "versions", "releases", "deployments" -> from

                else -> {
                    parts[4] = target.value
                    parts.joinToString("/") + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                }
            }
        }
        // A list names its catalog in the query (`?catalog=letters@…`), a resource page in the path.
        val catalogParam = Regex("(^|&)catalog=([^&]*)").find(query)
        if (catalogParam != null) {
            if (!isThisCatalog(java.net.URLDecoder.decode(catalogParam.groupValues[2], Charsets.UTF_8))) return null
            return path + "?" + query.replaceRange(catalogParam.groups[2]!!.range, target.value)
        }
        val segment = parts.getOrNull(4) ?: return null
        if (!isThisCatalog(segment)) return null
        parts[4] = target.value
        return parts.joinToString("/") + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
    }
}
