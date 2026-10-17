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
import app.epistola.suite.catalog.versions.CatalogVersionReleasedException
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
 * Which version of a catalog a page works in, and the controls for it (contract ADR 0002), in the
 * terms people use (docs/catalog-workspace-navigation.md): an authored catalog has **your work** —
 * its highest draft, which a plain segment (`letters`) means — and at times a **fix** of a published
 * version: any other draft, named by its number (`letters@1.4.1`). A subscribed catalog shows its
 * installed release, which cannot be edited.
 *
 * Every page that shows a catalog's content builds one of these and renders it as the bar above the
 * page header, so the catalog page and the content pages agree on what is being changed and on what
 * may be done to it. Templates gate on the names below, never on the raw flags.
 *
 * @property segment the route's catalog segment, which every link on the page carries.
 * @property chosen the draft worked in; null for a catalog that has none (subscribed, or every draft
 *   discarded).
 * @property mayOpenDrafts the viewer holds `TEMPLATE_EDIT`, which opening a draft needs (contract
 *   `createCatalogVersion`).
 * @property mayDiscardDrafts the viewer holds `CATALOG_RELEASE`, which discarding a draft needs (contract
 *   `deleteCatalogVersion`).
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
    /** The same page at another segment, for the draft links. */
    private val pageAt: (CatalogSegment) -> String,
) {
    /** The catalog's current version: its highest draft, or a subscribed catalog's installed release. */
    val current: CatalogVersion? get() = if (authored) drafts.firstOrNull() else installed

    /** The version the page shows: the chosen draft, or a subscribed catalog's installed release. */
    val shown: CatalogVersion? get() = chosen ?: installed

    /** Nothing on the page changes the catalog: it is subscribed, so its content is its publisher's. */
    val readOnly: Boolean get() = !authored

    /** The version messages on this page name; null for the current version. */
    val ref: CatalogVersionRef? get() = segment.ref(tenantKey)

    /** The segment links on this page carry, as it appears in a URL. */
    val segmentValue: String get() = segment.value

    /** More than one draft is open, so the page says which one it works in and offers the others. */
    val severalDrafts: Boolean get() = drafts.size > 1

    /**
     * An authored catalog with no open draft: there is nothing to edit until one is opened, so the
     * page offers to open one ([openDraftVersion] from [openDraftFrom]) instead of failing.
     */
    val noDraft: Boolean get() = authored && drafts.isEmpty()

    /**
     * The page works in a fix of a published version rather than in your work: an authored
     * catalog's draft other than its highest. Shown as a banner, so nobody mistakes it for their work.
     */
    val fixing: Boolean get() = authored && chosen != null && current != null && chosen.version != current?.version

    /** The published version a fix changes: the release its draft is based on. */
    val fixOf: String? get() = chosen?.base?.takeIf { fixing }

    /** The environments the fixed version is live in, so the banner says why the fix matters. */
    val fixOfLiveIn: List<String> get() = fixOf?.let { version -> releases.firstOrNull { it.version == version }?.deployedTo }.orEmpty()

    /** The same page in your work, for "back to your work" from a fix. */
    val workHref: String get() = pageAt(segment.at(null))

    /** The fixes open next to your work, each linked to this page in that fix. */
    val openFixes: List<DraftLink>
        get() = if (!authored || fixing) {
            emptyList()
        } else {
            drafts.drop(1).map { DraftLink(it.version, it.base, pageAt(segment.at(it.version)), active = false, behind = it.behind.isNotEmpty()) }
        }

    /**
     * The published versions a fix can be started on: an authored catalog's releases that kept their
     * content and are the newest patch of their line (fixing 1.4.0 once 1.4.1 exists would fix the
     * wrong thing), for a viewer who may edit. A LinkedHashSet: SpEL cannot call contains() on
     * Kotlin's EmptySet.
     */
    val fixableVersions: Set<String>
        get() = LinkedHashSet(
            if (!authored || !mayOpenDrafts) {
                emptyList()
            } else {
                releases.filter { it.contentRetained == true }.mapNotNull { release ->
                    val number = SemVer.parseOrNull(release.version) ?: return@mapNotNull null
                    val newerPatch = releases.any { other ->
                        SemVer.parseOrNull(other.version)?.let { it.major == number.major && it.minor == number.minor && it > number } == true
                    }
                    release.version.takeUnless { newerPatch }
                }
            },
        )

    /** Per published version with a fix open, the fix's number: "Continue the fix" instead of starting another. */
    val openFixOf: Map<String, String> get() = drafts.drop(1).mapNotNull { draft -> draft.base?.let { it to draft.version } }.toMap()

    /** Per published version, the environments it is live in. */
    val liveIn: Map<String, List<String>> get() = releases.associate { it.version to it.deployedTo }

    /** Publishing is withheld because the viewer may not publish; the bar says so instead of hiding it. */
    val publishWithheld: Boolean get() = authored && chosen != null && !mayDiscardDrafts

    /** The releases the chosen draft is behind, lowest first, each with what it changed. */
    val behind get() = chosen?.behind.orEmpty()

    /** Whether to offer opening another draft. */
    val offerCreateDraft: Boolean get() = authored && mayOpenDrafts

    /**
     * Whether to offer releasing the chosen draft. Releasing is catalog-wide — the draft goes out
     * whole — and the dialog says whether it is ready; it needs `CATALOG_RELEASE`.
     */
    val offerRelease: Boolean get() = authored && chosen != null && mayDiscardDrafts

    /** Whether to offer discarding the chosen draft. */
    val offerDiscard: Boolean get() = authored && mayDiscardDrafts && chosen != null

    /**
     * Whether to offer opening a draft where there is none. The viewer may, and either nothing was
     * ever released (an empty draft) or the latest release kept its content to copy.
     */
    val offerOpenDraft: Boolean get() = noDraft && mayOpenDrafts && (latestRelease == null || openDraftFrom != null)

    /** There is no draft and one cannot be opened from the latest release, which kept no content. */
    val openDraftWithheldNoContent: Boolean get() = noDraft && mayOpenDrafts && !offerOpenDraft

    /** Opening a draft is withheld because the viewer may not edit the catalog's content. */
    val draftControlsWithheld: Boolean get() = authored && !mayOpenDrafts

    /** Discarding the chosen draft is withheld because the viewer may not release the catalog. */
    val discardWithheld: Boolean get() = authored && chosen != null && !mayDiscardDrafts

    /** The number an opened draft gets when there is none: the patch after the latest release. */
    val openDraftVersion: String get() = latestRelease?.version?.let { SemVer.parseOrNull(it)?.bumpPatch()?.toString() } ?: "1.0.0"

    /** What an opened draft copies when there is none: the latest release that kept its content. */
    val openDraftFrom: String? get() = latestRelease?.takeIf { it.contentRetained == true }?.version

    /** The draft links, highest first, each to this page in that draft. */
    val draftLinks: List<DraftLink>
        get() = drafts.map { draft ->
            // Your work is linked plainly, a fix by its number.
            val target = if (draft.version == current?.version) segment.at(null) else segment.at(draft.version)
            DraftLink(draft.version, draft.base, pageAt(target), active = draft.version == chosen?.version, behind = draft.behind.isNotEmpty())
        }

    /** What a new draft can be copied from: every draft, and every release that kept its content. */
    val createFromOptions: List<String> get() = drafts.map { it.version } + releases.filter { it.contentRetained == true }.map { it.version }

    /** The number the create dialog suggests: the next minor above every version. */
    val suggestedNewVersion: String
        get() = (drafts + releases).mapNotNull { SemVer.parseOrNull(it.version) }.maxOrNull()?.bumpMinor()?.toString() ?: "1.0.0"

    private val latestRelease: CatalogVersion? get() = releases.firstOrNull()

    /** A draft to switch to. */
    data class DraftLink(val version: String, val base: String?, val href: String, val active: Boolean, val behind: Boolean)

    companion object {
        /**
         * The bar for [segment] of an existing catalog, for the current principal; [pageAt] builds the
         * page's URL at another segment. A segment naming a version the catalog does not have as a
         * draft is refused, as a message naming it would be.
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
            val chosen = when (val version = segment.version) {
                null -> drafts.firstOrNull()

                installed?.version -> null

                else -> drafts.firstOrNull { it.version == version } ?: run {
                    if (versions.any { it.version == version }) throw CatalogVersionReleasedException(segment.key, version)
                    throw CatalogVersionNotFoundException(segment.key, version)
                }
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
    fun browse(tenantId: TenantId, segment: CatalogSegment): String = "/tenants/${tenantId.key}/catalogs/${segment.value}/browse"
}
