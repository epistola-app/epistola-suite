// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
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
        val bar = model["draftBar"] as? CatalogDraftBar
        val catalogs = if (auth.has(Permission.CATALOG_VIEW)) {
            ListCatalogs(tenantKey).query()
                .filter { it.id != SYSTEM_CATALOG_KEY }
                .sortedWith(compareBy({ it.type != CatalogType.AUTHORED }, { it.name.lowercase() }))
                .map { CatalogLink(it.id.value, it.name, it.type == CatalogType.AUTHORED, CatalogPages.browse(TenantId(tenantKey), CatalogSegment(it.id)), it.id == bar?.catalogKey) }
        } else {
            emptyList()
        }

        if (bar != null) {
            val scope = scope(bar, model["workspaceVersion"] as? String, path) { auth.has(it) }
            remember(request, tenantKey, BackLink(path + (request.queryString?.let { "?$it" } ?: ""), backLabel(scope)))
            return Workspace(tenantKey, tenantName, catalogs, scope, back = null, section = null)
        }
        val section = nav.groups.flatMap { it.items }.firstOrNull { it.active }?.label
        val atHome = path.trimEnd('/') == "/tenants/${tenantKey.value}"
        return Workspace(tenantKey, tenantName, catalogs, catalog = null, back = recall(request, tenantKey), section = section, atHome = atHome)
    }

    companion object {
        private const val LAST_CATALOG = "epistola.workspace.lastCatalog"
        private const val SEPARATOR = "\u001F"

        /**
         * Being inside the catalog [bar] shows. [publishedVersion] is set by a published version's
         * page, which is not a draft: the bar then names that version instead of the work.
         */
        fun scope(bar: CatalogDraftBar, publishedVersion: String?, path: String, may: (Permission) -> Boolean): CatalogScope {
            val tenant = "/tenants/${bar.tenantKey.value}"
            val label = when {
                publishedVersion != null -> publishedVersion

                !bar.authored -> bar.shown?.let { "v${it.version}" } ?: "Not installed"

                bar.noDraft -> "Nothing in progress"

                bar.fixing -> bar.fixOf?.let { of -> "Fix of v$of" + (bar.fixOfLiveIn.firstOrNull()?.let { " · $it" } ?: "") }
                    ?: "Draft v${bar.chosen?.version}"

                else -> "Next version"
            }
            val versions = if (bar.authored) {
                bar.draftLinks.map { link ->
                    val next = link.version == bar.current?.version
                    VersionLink(
                        label = if (next) "Next version" else link.base?.let { "Fix of v$it" } ?: "Draft v${link.version}",
                        detail = if (next) link.base?.let { "Based on v$it" } else "Published as v${link.version}",
                        // Each version's overview, the same from every page: the bar stays the same
                        // within a catalog version, so a navigation there need not send it again.
                        href = if (next) CatalogPages.browse(TenantId(bar.tenantKey), bar.segment.at(null)) else CatalogPages.draft(TenantId(bar.tenantKey), bar.segment.at(link.version)),
                        current = link.active && publishedVersion == null,
                    )
                }
            } else {
                emptyList()
            }
            val fixLive = bar.releases.filter { it.deployedTo.isNotEmpty() }.flatMap { release ->
                val open = bar.openFixOf[release.version]
                val fixable = release.version in bar.fixableVersions
                if (open == null && !fixable) return@flatMap emptyList()
                release.deployedTo.map { environment ->
                    FixLive(
                        environment = environment,
                        version = release.version,
                        continueHref = open?.let { CatalogPages.draft(TenantId(bar.tenantKey), bar.segment.at(it)) },
                        startHref = if (open == null) "$tenant/catalogs/${bar.catalogKey.value}/fixes" else null,
                    )
                }
            }
            return CatalogScope(
                bar = bar,
                versionLabel = label,
                sideWork = bar.fixing,
                sections = CatalogNav.sections(bar, path, may),
                versions = versions,
                fixLive = fixLive,
                statusHref = if (bar.authored && publishedVersion == null) "$tenant/catalogs/${bar.segmentValue}/bar-status" else null,
                versionsHref = "$tenant/catalogs/${bar.catalogKey.value}/versions",
            )
        }

        /** How the way back names where someone was: "Back to Letters", and the fix when it was one. */
        fun backLabel(scope: CatalogScope): String = "Back to ${scope.name}" + if (scope.sideWork) ", ${scope.versionLabel.substringBefore(" · ").replaceFirstChar { it.lowercase() }}" else ""

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
