// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.Permission

/**
 * The frame around every tenant page (docs/workspace-shell.md): a context bar that always says
 * where someone is, and a side nav for the place they are in.
 *
 * There are two places. **Inside a catalog** — every page that shows a catalog's content — the bar
 * reads `Acme / Letters / Next version`, with what is not published yet and Publish, and the side
 * nav is the catalog's own: its overview, templates, the parts templates are built from, and its
 * versions. **Outside a catalog** — environments, settings, operations — the bar reads `Acme /
 * Environments`, with a way back to the catalog someone came from, and the side nav is the
 * tenant's, listing their catalogs.
 *
 * Which place a page is in follows from its model: a page that shows a catalog's content passes a
 * [CatalogDraftBar] as `draftBar`, as it already did for the old catalog bar.
 *
 * @property catalogs the catalogs people work with — authored and subscribed — for the catalog
 *   switcher. The system catalog, part of every tenant, is left out.
 * @property catalog where someone is inside a catalog; null outside one.
 * @property back outside a catalog, the catalog someone came from.
 * @property section outside a catalog, what the page is called, for the bar.
 */
data class Workspace(
    val tenantKey: TenantKey,
    val tenantName: String,
    val catalogs: List<CatalogLink>,
    /** The catalogs changed most recently, for the tenant's side nav; [catalogs] are all of them. */
    val recentCatalogs: List<CatalogLink> = emptyList(),
    val catalog: CatalogScope?,
    val back: BackLink?,
    val section: String?,
    /** The page is the tenant's home. */
    val atHome: Boolean = false,
) {
    /**
     * What the context bar shows, as a short key: a boosted navigation that keeps it gets the bar
     * sent again only when this changes (docs/workspace-shell.md). Everything the bar renders goes
     * in, the catalog's nav does not: that is sent with every page.
     */
    val barKey: String
        get() = listOf(
            tenantKey.value,
            tenantName,
            catalogs,
            catalog?.let { listOf(it.bar.segmentValue, it.name, it.versionLabel, it.sideWork, it.readOnly, it.versions, it.statusHref, it.versionsHref) },
            back,
            section,
        ).hashCode().toUInt().toString(36)

    /** The tenant's home page, which the logo and the tenant crumb lead to. */
    val homeHref: String get() = "/tenants/${tenantKey.value}"

    /** The catalogs someone authors, then the ones they subscribed to, each a group in the switcher. */
    val authoredCatalogs: List<CatalogLink> get() = catalogs.filter { it.authored }
    val subscribedCatalogs: List<CatalogLink> get() = catalogs.filterNot { it.authored }
}

/** A catalog in the switcher and the tenant nav. [current] marks the one someone is in. */
data class CatalogLink(val key: String, val name: String, val authored: Boolean, val href: String, val current: Boolean)

/** Back to the catalog someone came from, named with the version they were in. */
data class BackLink(val href: String, val label: String)

/** A link in a side nav; [icon] names an icon in the sprite. */
data class NavLink(val key: String, val label: String, val href: String, val icon: String, val active: Boolean)

/**
 * Being inside a catalog: which version the page works in, and the catalog's nav.
 *
 * @property versionLabel what the bar calls the version: "Next version", "Fix of v1.2.0", or a
 *   subscribed catalog's "v2.0.0".
 * @property sideWork the page works in long-term work, not the next version, which the bar marks so
 *   nobody edits it thinking it is their everyday work.
 * @property sections the catalog nav, in groups: the catalog and its templates; the parts templates
 *   are built from; its versions, where it is live, what it uses and its details.
 * @property versions the drafts to switch to, the next version first.
 * @property statusHref the bar's status — what is not published yet, and Publish — loaded after the
 *   page, because working it out builds the catalog.
 */
data class CatalogScope(
    val bar: CatalogDraftBar,
    val versionLabel: String,
    val sideWork: Boolean,
    val sections: List<List<NavLink>>,
    val versions: List<VersionLink>,
    val statusHref: String?,
    val versionsHref: String,
) {
    val name: String get() = bar.catalogName
    val readOnly: Boolean get() = bar.readOnly

    /** The version menu has something to switch to or start, beyond the published versions. */
    val offerVersionMenu: Boolean get() = bar.authored
}

/** A draft in the version menu. [detail] says what it is based on. */
data class VersionLink(val label: String, val detail: String?, val href: String, val current: Boolean)

/** What a catalog's side nav is built from; kept apart so its links and gating are tested alone. */
object CatalogNav {

    private data class Part(val key: String, val label: String, val area: String, val icon: String, val permission: Permission)

    /** The parts templates are built from, in the order people reach for them. */
    private val PARTS = listOf(
        Part("stencils", "Stencils", "stencils", "layers", Permission.STENCIL_VIEW),
        Part("themes", "Themes", "themes", "palette", Permission.THEME_VIEW),
        Part("images", "Images", "images", "image", Permission.TEMPLATE_VIEW),
        Part("fonts", "Fonts", "fonts", "type", Permission.REFERENCE_VIEW),
        Part("attributes", "Attributes", "attributes", "tag", Permission.REFERENCE_VIEW),
        Part("code-lists", "Code lists", "code-lists", "list", Permission.REFERENCE_VIEW),
    )

    /**
     * The catalog nav for [bar], with the item for [path] marked active; [may] says whether the viewer
     * holds a permission, [qualityAvailable] whether the quality feature is on. Content pages carry the page's segment, so side work stays side work as
     * someone moves around; versions and where it is live are the same for every version and carry
     * the catalog's plain key.
     */
    fun sections(bar: CatalogDraftBar, path: String, may: (Permission) -> Boolean, qualityAvailable: Boolean = false): List<List<NavLink>> {
        val tenant = "/tenants/${bar.tenantKey.value}"
        val segment = bar.segmentValue
        val key = bar.catalogKey.value
        val active = activeKey(path, key)
        fun link(key: String, label: String, href: String, icon: String) = NavLink(key, label, href, icon, active == key)

        val overview = if (bar.onLongTermWork) bar.draftPageHref else CatalogPages.browse(TenantId(bar.tenantKey), bar.segment)
        val first = buildList {
            add(link("overview", "Overview", overview, "house"))
            // Quality findings are about this catalog's templates (alpha, behind its feature).
            if (qualityAvailable && may(Permission.TEMPLATE_VIEW)) add(link("quality", "Quality", "$tenant/quality?catalog=$key", "scan"))
        }
        // What the catalog holds, templates first: what documents are made from, then what they are built from.
        val parts = buildList {
            if (may(Permission.TEMPLATE_VIEW)) add(link("templates", "Templates", "$tenant/catalogs/$segment/templates", "file-text"))
            addAll(PARTS.filter { may(it.permission) }.map { link(it.key, it.label, "$tenant/catalogs/$segment/${it.area}", it.icon) })
        }
        val catalog = listOf(
            link("versions", "Versions", "$tenant/catalogs/$key/versions", "history"),
            link("deployments", "Deployments", "$tenant/catalogs/$key/deployments", "radio"),
            link("dependencies", "Dependencies", "$tenant/catalogs/$segment/dependencies", "link"),
            link("settings", "Settings", "$tenant/catalogs/$segment/settings", "settings"),
        )
        return listOf(first, parts, catalog).filter { it.isNotEmpty() }
    }

    /** A catalog section in one version: where a page missing from that version leads. */
    data class SectionInVersion(val label: String, val href: String, val overviewHref: String)

    /**
     * The section and catalog version of a page about one resource
     * (`/tenants/{t}/{area}/{catalog}/{key}…`), so a page missing from that version (switching to a
     * version without that template) leads to the section's list there instead of a dead end; null
     * for anything else.
     */
    fun sectionOf(path: String): SectionInVersion? {
        val parts = path.substringBefore('?').trim('/').split('/')
        if (parts.size < 5 || parts[0] != "tenants") return null
        val (tenant, area, segment) = Triple(parts[1], parts[2], parts[3])
        val label = when (area) {
            "templates" -> "Templates"
            in PARTS.map { it.area } -> PARTS.first { it.area == area }.label
            else -> return null
        }
        val overview = if ('@' in segment) "/tenants/$tenant/catalogs/$segment/draft" else "/tenants/$tenant/catalogs/$segment/browse"
        return SectionInVersion(label, "/tenants/$tenant/catalogs/$segment/$area", overview)
    }

    /** Which catalog nav item [path] belongs to; null when none does. */
    fun activeKey(path: String, catalogKey: String): String? {
        val parts = path.substringBefore('?').trim('/').split('/')
        // "tenants", tenant, area, catalog, page
        val area = parts.getOrNull(2) ?: return null
        if (area == "catalogs") {
            val page = parts.getOrNull(4)
            return when (page) {
                "browse", "draft", null -> "overview"

                "releases", "versions" -> "versions"

                "deployments" -> "deployments"

                "dependencies" -> "dependencies"

                "settings" -> "settings"

                // A list is the catalog's: `/catalogs/{catalog}/templates`.
                "templates" -> "templates"

                in PARTS.map { it.area } -> PARTS.first { it.area == page }.key

                else -> null
            }.takeIf { parts.getOrNull(3)?.substringBefore('@') == catalogKey }
        }
        return when (area) {
            "templates" -> "templates"
            "quality" -> "quality"
            in PARTS.map { it.area } -> PARTS.first { it.area == area }.key
            else -> null
        }
    }
}
