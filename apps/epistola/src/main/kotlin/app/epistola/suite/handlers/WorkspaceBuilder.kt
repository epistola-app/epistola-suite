// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ListRecentCatalogs
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.versions.CatalogDraft
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
                runCatching { CatalogDraftBar.of(tenantKey, segment) { "$path?catalog=${it.value}" } }.getOrNull()
            }
        val recent = if (auth.has(Permission.CATALOG_VIEW)) {
            ListRecentCatalogs(tenantKey).query()
                .map { CatalogLink(it.id.value, it.name, it.type == CatalogType.AUTHORED, CatalogPages.browse(TenantId(tenantKey), CatalogSegment(it.id)), it.id == bar?.catalogKey) }
        } else {
            emptyList()
        }
        val catalogs = if (auth.has(Permission.CATALOG_VIEW)) {
            ListCatalogs(tenantKey).query()
                .filter { it.id != SYSTEM_CATALOG_KEY }
                .sortedWith(compareBy({ it.type != CatalogType.AUTHORED }, { it.name.lowercase() }))
                .map { CatalogLink(it.id.value, it.name, it.type == CatalogType.AUTHORED, CatalogPages.browse(TenantId(tenantKey), CatalogSegment(it.id)), it.id == bar?.catalogKey) }
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
                !bar.authored -> bar.shown?.let { "v${it.version}" } ?: "Not installed"
                bar.noDraft -> "Nothing in progress"
                else -> bar.draftTitle ?: CatalogDraft.NEXT_LABEL
            }
            val versions = if (bar.authored) {
                bar.draftLinks.map { link ->
                    VersionLink(
                        label = link.label,
                        detail = when {
                            link.next -> link.base?.let { "Based on v$it" }
                            else -> "Long-term work" + (link.base?.let { ", started from v$it" } ?: "")
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
                sideWork = bar.onLongTermWork,
                sections = CatalogNav.sections(bar, path, may, qualityAvailable),
                versions = versions,
                statusHref = if (bar.authored && publishedVersion == null) "$tenant/catalogs/${bar.segmentValue}/bar-status" else null,
                versionsHref = "$tenant/catalogs/${bar.catalogKey.value}/versions",
            )
        }

        /** How the way back names where someone was: "Back to Letters", and the long-term work when it was that. */
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
