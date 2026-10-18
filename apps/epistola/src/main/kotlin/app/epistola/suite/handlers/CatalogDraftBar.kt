// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext

/**
 * Which version of a catalog a page works in, and the controls for it (contract ADR 0002, amendment 2),
 * in the terms people use (docs/catalog-working-model.md): an authored catalog has a **next version** —
 * where everyday work goes, which a plain segment (`letters`) means and which has no number until it is
 * published — and at times **long-term work** next to it, a named draft (`letters@<draft id>`) that is
 * not published until it becomes the next version. A subscribed catalog shows its installed release,
 * which cannot be edited.
 *
 * Every page that shows a catalog's content builds one of these and renders it as the bar above the
 * page header, so the catalog page and the content pages agree on what is being changed and on what
 * may be done to it. Templates gate on the names below, never on the raw flags.
 *
 * @property segment the route's catalog segment, which every link on the page carries.
 * @property drafts the open drafts: the next version first, then long-term work.
 * @property chosen the draft worked in; null for a catalog that has none (subscribed, or a next version
 *   discarded and not opened again).
 * @property mayOpenDrafts the viewer holds `TEMPLATE_EDIT`, which starting long-term work needs.
 * @property mayDiscardDrafts the viewer holds `CATALOG_RELEASE`, which publishing and discarding need.
 */
data class CatalogDraftBar(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val catalogName: String,
    val authored: Boolean,
    val segment: CatalogSegment,
    val drafts: List<CatalogVersion>,
    val releases: List<CatalogVersion>,
    val chosen: CatalogVersion?,
    val mayOpenDrafts: Boolean,
    val mayDiscardDrafts: Boolean,
    /** A subscribed catalog's installed release: the version its pages show. Null for an authored catalog. */
    val installed: CatalogVersion? = null,
    /** The viewer holds `CATALOG_MANAGE`, which deleting a published version needs. */
    val mayDeleteReleases: Boolean = false,
    /** The same page at another segment, for the draft links. */
    private val pageAt: (CatalogSegment) -> String,
) {
    /** The catalog's current version: its next version, or a subscribed catalog's installed release. */
    val current: CatalogVersion? get() = if (authored) drafts.firstOrNull { it.isNext } else installed

    /** The version the page shows: the chosen draft, or a subscribed catalog's installed release. */
    val shown: CatalogVersion? get() = chosen ?: installed

    /** Nothing on the page changes the catalog: it is subscribed, so its content is its publisher's. */
    val readOnly: Boolean get() = !authored

    /** The version messages on this page name; null for the next version. */
    val ref: CatalogVersionRef? get() = segment.ref(tenantKey)

    /** The segment links on this page carry, as it appears in a URL. */
    val segmentValue: String get() = segment.value

    /** Long-term work is open next to the next version, so the page says which draft it works in. */
    val severalDrafts: Boolean get() = drafts.size > 1

    /**
     * An authored catalog with no next version: there is nothing to edit until it is opened, so the
     * page offers to open it (on [openDraftFrom]) instead of failing.
     */
    val noDraft: Boolean get() = authored && current == null

    /**
     * The page works in long-term work, not the next version. Shown as a banner, so nobody mistakes it
     * for everyday work, and publishing is not offered: only the next version is published.
     */
    val onLongTermWork: Boolean get() = authored && chosen != null && !chosen.isNext

    /** The same page in the next version, for "go to the next version" from long-term work. */
    val workHref: String get() = pageAt(segment.at(null))

    /** The chosen draft's own page, for the bar above a content page. */
    val draftPageHref: String get() = CatalogPages.draft(TenantId(tenantKey), segment)

    /** Long-term work open next to the next version, each linked to this page in that draft. */
    val longTermWork: List<DraftLink> get() = draftLinks.filter { !it.next }

    /** Per published version, the environments it is live in. */
    val liveIn: Map<String, List<String>> get() = releases.associate { requireNotNull(it.version) to it.deployedTo }

    // ---- The catalog page, as people see it (docs/catalog-working-model.md) ----

    /** The page works in the next version, the one people work in 99% of the time. */
    val isNext: Boolean get() = authored && chosen?.isNext == true

    /** What the chosen draft is called: "Next version", or long-term work's name. */
    val draftTitle: String? get() = chosen?.label

    /** The latest published version: what the next version is based on. */
    val latestPublished: String? get() = latestRelease?.version

    /**
     * Every version of the catalog, for the catalog page's Versions list: an authored catalog's
     * drafts, the next version first, then the published versions (a subscribed catalog's installed
     * ones), newest first. Each links to its own page.
     */
    val versionRows: List<VersionRow>
        get() = (if (authored) drafts else emptyList()).map { draft ->
            val at = if (draft.isNext) segment.at(null) else segment.at(draft.draftId)
            VersionRow(
                label = draft.label,
                version = null,
                draft = true,
                next = draft.isNext,
                base = draft.base,
                publishedAt = null,
                liveIn = emptyList(),
                contentKept = true,
                href = pageAt(at),
                offerRemove = mayDiscardDrafts,
                removeWithheld = !mayDiscardDrafts,
                removeAction = "/tenants/${tenantKey.value}/catalogs/${at.value}/drafts/discard",
            )
        } + releases.map { release ->
            val version = requireNotNull(release.version)
            VersionRow(
                label = release.label,
                version = version,
                draft = false,
                next = false,
                base = release.base,
                publishedAt = release.releasedAt,
                liveIn = release.deployedTo,
                contentKept = release.contentRetained == true,
                href = CatalogPages.release(tenantKey, catalogKey, version),
                // A subscribed catalog's versions are its publisher's: nothing to delete here.
                offerRemove = authored && mayDeleteReleases,
                removeWithheld = authored && !mayDeleteReleases,
                removeAction = "/tenants/${tenantKey.value}/catalogs/${catalogKey.value}/releases/$version/delete",
                preRelease = SemVer.parseOrNull(version)?.isPreRelease == true,
            )
        }

    /**
     * One version on the catalog page: a draft by [label] ("Next version", or long-term work's name), a
     * published one by [version]; [base] is the published version it started from (null when it
     * started from nothing); [publishedAt] is null for a draft.
     */
    data class VersionRow(
        val label: String,
        val version: String?,
        val draft: Boolean,
        val next: Boolean,
        val base: String?,
        val publishedAt: java.time.OffsetDateTime?,
        val liveIn: List<String>,
        val contentKept: Boolean,
        val href: String,
        /** Offer removing it from the row: discarding a draft, deleting a published version. */
        val offerRemove: Boolean = false,
        /** Removing it is withheld for want of the role, which the page says once. */
        val removeWithheld: Boolean = false,
        /** Where removing it posts: a draft is discarded at its segment, a published version deleted. */
        val removeAction: String = "",
        /** A pre-release of long-term work: used only where it is deployed by its number. */
        val preRelease: Boolean = false,
    )

    /** Some row's removal is withheld, so the Versions page says what it needs. */
    val removalWithheld: Boolean get() = versionRows.any { it.removeWithheld }

    /** Publishing is withheld because the viewer may not publish; the bar says so instead of hiding it. */
    val publishWithheld: Boolean get() = isNext && !mayDiscardDrafts

    /** The releases the chosen draft is behind, lowest first, each with what it changed. */
    val behind get() = chosen?.behind.orEmpty()

    /** Whether to offer starting long-term work. */
    val offerCreateDraft: Boolean get() = authored && mayOpenDrafts && current != null

    /**
     * Whether to offer publishing the chosen draft. Only the next version is published; publishing is
     * catalog-wide — the draft goes out whole — and the dialog says whether it is ready; it needs
     * `CATALOG_RELEASE`.
     */
    val offerRelease: Boolean get() = isNext && mayDiscardDrafts

    /** Whether to offer publishing long-term work as a pre-release, to try it (`CATALOG_RELEASE`). */
    val offerPreRelease: Boolean get() = onLongTermWork && mayDiscardDrafts

    /** Whether to offer making long-term work the next version (`CATALOG_RELEASE`). */
    val offerPromote: Boolean get() = onLongTermWork && mayDiscardDrafts

    /** Whether to offer discarding the chosen draft. */
    val offerDiscard: Boolean get() = authored && mayDiscardDrafts && chosen != null

    /**
     * Whether to offer opening the next version where there is none. The viewer may, and either nothing
     * was ever released (an empty draft) or the latest release kept its content to copy.
     */
    val offerOpenDraft: Boolean get() = noDraft && mayOpenDrafts && (latestRelease == null || openDraftFrom != null)

    /** There is no next version and one cannot be opened from the latest release, which kept no content. */
    val openDraftWithheldNoContent: Boolean get() = noDraft && mayOpenDrafts && !offerOpenDraft

    /** Starting work is withheld because the viewer may not edit the catalog's content. */
    val draftControlsWithheld: Boolean get() = authored && !mayOpenDrafts

    /** Discarding the chosen draft is withheld because the viewer may not release the catalog. */
    val discardWithheld: Boolean get() = authored && chosen != null && !mayDiscardDrafts

    /** What an opened next version copies when there is none: the latest release that kept its content. */
    val openDraftFrom: String? get() = latestRelease?.takeIf { it.contentRetained == true }?.version

    /** The draft links, the next version first, each to this page in that draft. */
    val draftLinks: List<DraftLink>
        get() = drafts.map { draft ->
            // The next version is linked plainly, long-term work by its id.
            val target = if (draft.isNext) segment.at(null) else segment.at(draft.draftId)
            DraftLink(draft.label, draft.isNext, draft.draftId, draft.base, pageAt(target), active = draft.draftId == chosen?.draftId, behind = draft.behind.isNotEmpty())
        }

    /** What long-term work can start from: the next version (null), or a release that kept its content. */
    val createFromOptions: List<String> get() = releases.filter { it.contentRetained == true }.mapNotNull { it.version }

    private val latestRelease: CatalogVersion? get() = releases.firstOrNull()

    /** A draft to switch to: the [next] version, or long-term work by [label] and [draftId]. */
    data class DraftLink(val label: String, val next: Boolean, val draftId: java.util.UUID?, val base: String?, val href: String, val active: Boolean, val behind: Boolean)

    companion object {
        /**
         * The bar for [segment] of an existing catalog, for the current principal; [pageAt] builds the
         * page's URL at another segment. A segment naming a draft the catalog does not have is refused,
         * as a message naming it would be.
         */
        fun of(tenantKey: TenantKey, segment: CatalogSegment, pageAt: (CatalogSegment) -> String): CatalogDraftBar {
            val catalog = GetCatalog(tenantKey, segment.key).query()
            val versions = ListCatalogVersions(tenantKey, segment.key).query()
            val drafts = versions.filter { it.status == CatalogVersionStatus.DRAFT }
            val authored = catalog?.type == CatalogType.AUTHORED
            // A subscribed catalog shows its installed release, the only version whose content it has.
            val installed = catalog?.installedReleaseVersion?.takeUnless { authored }?.let { number ->
                versions.firstOrNull { it.version == number } ?: CatalogVersion(number, CatalogVersionStatus.RELEASED)
            }
            val chosen = when (val draft = segment.draft) {
                null -> drafts.firstOrNull { it.isNext }
                else -> drafts.firstOrNull { it.draftId == draft } ?: throw CatalogVersionNotFoundException(segment.key, draft.toString())
            }
            val auth = AuthContext.from(SecurityContext.current(), tenantKey)
            return CatalogDraftBar(
                tenantKey = tenantKey,
                catalogKey = segment.key,
                catalogName = catalog?.name ?: segment.key.value,
                authored = authored,
                segment = segment,
                drafts = drafts,
                releases = versions.filter { it.status == CatalogVersionStatus.RELEASED },
                chosen = chosen,
                mayOpenDrafts = auth.has(Permission.TEMPLATE_EDIT),
                mayDiscardDrafts = auth.has(Permission.CATALOG_RELEASE),
                installed = installed,
                mayDeleteReleases = auth.has(Permission.CATALOG_MANAGE),
                pageAt = pageAt,
            )
        }

        /**
         * [pageAt] for a page whose path holds the segment where every per-catalog UI route has it,
         * `/tenants/{tenantId}/{area}/{catalogId}/…`; the query string is kept.
         */
        fun inPath(path: String, segment: CatalogSegment, query: String? = null): (CatalogSegment) -> String = { target ->
            val parts = path.split('/').toMutableList()
            if (parts.size > CATALOG_PART && parts[CATALOG_PART] == segment.value) parts[CATALOG_PART] = target.value
            parts.joinToString("/") + (query?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: "")
        }

        /** The index of `{catalogId}` in a split UI path: "", "tenants", tenant, area, catalog. */
        private const val CATALOG_PART = 4
    }
}

/** URLs of the catalog page, at a catalog segment (a draft, or the current version). */
object CatalogPages {
    /** The catalog page: the next version, every version, where each is live. */
    fun browse(tenantId: TenantId, segment: CatalogSegment): String = "/tenants/${tenantId.key}/catalogs/${segment.key.value}/browse"

    /** A draft's own page: the next version at a plain segment, long-term work at `key@<draft id>`. */
    fun draft(tenantId: TenantId, segment: CatalogSegment): String = "/tenants/${tenantId.key}/catalogs/${segment.value}/draft"

    /** A published version's own page. */
    fun release(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): String = "/tenants/${tenantKey.value}/catalogs/${catalogKey.value}/releases/$version"
}
