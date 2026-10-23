// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogAddresses
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
        // By key: the address is named again where it is used (CatalogAddresses).
        val path = CatalogAddresses.byKey(from.substringBefore('?'))
        val query = from.substringAfter('?', missingDelimiterValue = "")
        val key = target.key.value
        val parts = path.split('/').toMutableList() // "", "tenants", tenant, "catalogs", catalog, …
        fun isThisCatalog(segment: String) = segment.substringBefore('@') == key

        if (parts.getOrNull(3) == "catalogs") {
            val segment = parts.getOrNull(4) ?: return null
            if (!isThisCatalog(segment)) return null
            return when (parts.getOrNull(5)) {
                // A version's overview has its own address: the next version's, or the work's.
                null, "browse", "draft" -> if (target.draft == null) "/tenants/$tenantKey/catalogs/$key/browse" else "/tenants/$tenantKey/catalogs/${target.copy(namespace = null).value}/draft"

                // The same for every version.
                "versions", "releases", "deployments" -> from

                else -> {
                    parts[4] = target.copy(namespace = null).value
                    parts.joinToString("/") + (query.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
                }
            }
        }
        // Every page of a catalog is under `/catalogs/{catalog}/`; anything else is not one.
        return null
    }
}
