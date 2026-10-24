// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.queries.CatalogEditabilities
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListRecentCatalogs
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.nav.NavModel
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Builds the [Workspace] for a full page: which place the page is in, and the bar and nav for it.
 *
 * The catalog someone was last in is kept in their session, so a page outside any catalog can offer
 * the way back to it. It is written only when it changes; sessions are stored in the database.
 */
@Component
class WorkspaceBuilder {

    fun build(
        tenantKey: TenantKey,
        tenantName: String,
        auth: AuthContext,
        model: Map<String, Any?>,
        request: HttpServletRequest,
        path: String,
        nav: NavModel,
    ): Workspace {
        // A page in a catalog passes its draft bar; a feature module, which cannot build one, the
        // catalog it shows as `workspaceCatalog`.
        val bar = model["draftBar"] as? CatalogDraftBar
            ?: (model["workspaceCatalog"] as? CatalogSegment)?.let { segment ->
                runCatching { CatalogDraftBar.of(tenantKey, segment) { VersionSwitch.samePage(path, tenantKey.value, it) ?: "$path?catalog=${it.value}" } }.getOrNull()
            }
        // One read of which catalogs can be changed here, for both lists.
        val editability by lazy { ListCatalogEditability(tenantKey).query() }
        val all by lazy { ListCatalogs(tenantKey).query() }

        // Each catalog by its address (ADR 0034, section 9).
        fun link(catalog: Catalog): CatalogLink = catalogLink(tenantKey, catalog, editability, current = bar != null && catalog.address == bar.segment.address)
        val recent = if (auth.has(Permission.CATALOG_VIEW)) {
            ListRecentCatalogs(tenantKey).query().map(::link)
        } else {
            emptyList()
        }
        val catalogs = if (auth.has(Permission.CATALOG_VIEW)) {
            all
                .filter { it.id != SYSTEM_CATALOG_KEY }
                .map(::link)
                .sortedWith(compareBy({ !it.editable }, { it.name.lowercase() }))
        } else {
            emptyList()
        }

        if (bar != null) {
            val quality = ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.QUALITY] == true
            val scope = scope(bar, model["workspaceVersion"] as? String, path, { auth.has(it) }, quality)
            remember(request, tenantKey, BackLink(path + (request.queryString?.let { "?$it" } ?: ""), backLabel(scope)))
            return Workspace(tenantKey, tenantName, catalogs, recent, scope, back = null, section = null)
        }
        val section = nav.groups.flatMap { it.items }.firstOrNull { it.active }?.label
        val atHome = path.trimEnd('/') == "/tenants/${tenantKey.value}"
        return Workspace(tenantKey, tenantName, catalogs, recent, catalog = null, back = recall(request, tenantKey), section = section, atHome = atHome)
    }

    companion object {
        /** A catalog for the switcher and the tenant nav, by its address, saying whether it can be changed here. */
        fun catalogLink(tenantKey: TenantKey, catalog: Catalog, editability: CatalogEditabilities, current: Boolean): CatalogLink {
            val reason = editability.of(catalog.catalogId)?.reason
            val segment = CatalogSegment(catalog.id, namespace = catalog.namespace)
            return CatalogLink(catalog.id.value, catalog.name, editability.editable(catalog.catalogId), CatalogPages.browse(TenantId(tenantKey), segment), current, reason?.label)
        }

        private const val LAST_CATALOG = "epistola.workspace.lastCatalog"
        private const val SEPARATOR = "\u001F"

        /**
         * Being inside the catalog [bar] shows. [publishedVersion] is set by a published version's
         * page, which is not a draft: the bar then names that version instead of the work.
         */
        fun scope(bar: CatalogDraftBar, publishedVersion: String?, path: String, may: (Permission) -> Boolean, qualityAvailable: Boolean = false): CatalogScope {
            val tenant = "/tenants/${bar.tenantKey.value}"
            val label = when {
                publishedVersion != null -> publishedVersion
                !bar.editable -> bar.shown?.let { "v${it.version}" } ?: "No release yet"
                else -> bar.draftTitle ?: CatalogDraft.NEXT_LABEL
            }
            val versions = if (bar.editable) {
                bar.draftLinks.map { link ->
                    VersionLink(
                        label = link.label,
                        detail = when {
                            link.next -> link.base?.let { "Based on v$it" }
                            else -> "Parallel work" + (link.base?.let { ", started from v$it" } ?: "")
                        },
                        // The same page in that version (VersionSwitch). The link is the same on every
                        // page, so the bar is not sent again while moving around in a version.
                        href = "$tenant/catalogs/${bar.segment.at(link.draftId.takeUnless { link.next }).value}/switch",
                        current = link.active && publishedVersion == null,
                    )
                }
            } else {
                emptyList()
            }
            return CatalogScope(
                bar = bar,
                versionLabel = label,
                sideWork = bar.onParallelWork,
                sections = CatalogNav.sections(bar, path, may, qualityAvailable),
                versions = versions,
                statusHref = if (bar.editable && publishedVersion == null) "$tenant/catalogs/${bar.segmentValue}/bar-status" else null,
                versionsHref = "$tenant/catalogs/${bar.segment.address}/releases",
            )
        }

        /** How the way back names where someone was: "Back to Letters", and the parallel work when it was that. */
        fun backLabel(scope: CatalogScope): String = "Back to ${scope.name}" + if (scope.sideWork) ", ${scope.versionLabel}" else ""

        private fun remember(request: HttpServletRequest, tenantKey: TenantKey, back: BackLink) {
            val value = listOf(tenantKey.value, back.href, back.label).joinToString(SEPARATOR)
            val session = request.getSession(false) ?: return
            if (session.getAttribute(LAST_CATALOG) != value) session.setAttribute(LAST_CATALOG, value)
        }

        private fun recall(request: HttpServletRequest, tenantKey: TenantKey): BackLink? {
            val value = request.getSession(false)?.getAttribute(LAST_CATALOG) as? String ?: return null
            val (tenant, href, label) = value.split(SEPARATOR).takeIf { it.size == 3 } ?: return null
            return BackLink(href, label).takeIf { tenant == tenantKey.value }
        }
    }
}
