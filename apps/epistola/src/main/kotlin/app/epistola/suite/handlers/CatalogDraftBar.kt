// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.catalog.queries.CatalogReadOnlyReason
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.catalog.releases.CatalogRelease
import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.queries.ListDraftDeployments
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext

/**
 * Which version of a catalog a page works in, and the controls for it (contract ADR 0002, amendment 2),
 * in the terms people use (docs/catalog-working-model.md): a catalog that can be changed here has a
 * **next version** — where everyday work goes, which a plain segment (`letters`) means and which has
 * no number until it is published — and at times **parallel work** next to it, a named draft
 * (`letters@<draft id>`) that is not published until it becomes the next version. A catalog that
 * cannot be changed here (ADR 0032: Epistola supplies it, or the installation only runs releases)
 * shows the release its content is on, and offers nothing that changes it.
 *
 * Every page that shows a catalog's content builds one of these and renders it as the bar above the
 * page header, so the catalog page and the content pages agree on what is being changed and on what
 * may be done to it. Templates gate on the names below, never on the raw flags.
 *
 * @property segment the route's catalog segment, which every link on the page carries.
 * @property drafts the open drafts: the next version first, then parallel work.
 * @property chosen the draft worked in; null for a catalog that cannot be changed here, or whose next
 *   version was discarded and not opened again.
 * @property mayOpenDrafts the viewer holds `TEMPLATE_EDIT`, which starting parallel work needs.
 * @property mayDiscardDrafts the viewer holds `CATALOG_RELEASE`, which publishing and discarding need.
 */
data class CatalogDraftBar(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val catalogName: String,
    /** Whether the catalog can be changed here (ADR 0032); [readOnlyReason] says why not. */
    val editable: Boolean,
    val segment: CatalogSegment,
    val drafts: List<CatalogRelease>,
    val releases: List<CatalogRelease>,
    val chosen: CatalogRelease?,
    val mayOpenDrafts: Boolean,
    val mayDiscardDrafts: Boolean,
    /**
     * For a catalog that cannot be changed here, the release its pages show: the one its next version
     * is on, else its latest. Null for a catalog that can be changed.
     */
    val installed: CatalogRelease? = null,
    /** Why the catalog cannot be changed here; null when it can. */
    val readOnlyReason: CatalogReadOnlyReason? = null,
    /** Epistola supplies the catalog, so its releases are the suite's too: nothing to delete here. */
    val suppliedByEpistola: Boolean = false,
    /** The viewer holds `CATALOG_MANAGE`, which deleting a published version needs. */
    val mayDeleteReleases: Boolean = false,
    /** The viewer holds `ENVIRONMENT_DEPLOY`, which pointing an environment at a version needs. */
    val mayDeploy: Boolean = false,
    /** The environments that follow [chosen], live (an override naming it), by name. */
    val followedBy: List<String> = emptyList(),
    /** The same page at another segment, for the draft links. */
    private val pageAt: (CatalogSegment) -> String,
) {
    /** Whether to offer trying [chosen] on an environment, which then follows it as it is changed. */
    val offerFollow: Boolean get() = editable && chosen != null && mayDeploy

    /** "Test and Acceptance follow it", or null when no environment does. */
    val followedByText: String? get() = followedBy.takeIf { it.isNotEmpty() }?.let { names ->
        (if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()) +
            (if (names.size == 1) " follows it" else " follow it")
    }

    /** The catalog's current version: its next version, or the release a read-only catalog is on. */
    val current: CatalogRelease? get() = if (editable) drafts.firstOrNull { it.isNext } else installed

    /** The version the page shows: the chosen draft, or the release a read-only catalog is on. */
    val shown: CatalogRelease? get() = chosen ?: installed

    /** Nothing on the page changes the catalog: it cannot be changed here ([readOnlyReason]). */
    val readOnly: Boolean get() = !editable

    /** A short reason the catalog is read-only, for a badge or the bar: "Supplied by Epistola". */
    val readOnlyLabel: String? get() = readOnlyReason?.label

    /** The reason in a sentence, for a tooltip or a note. */
    val readOnlyExplanation: String? get() = readOnlyReason?.explanation

    /** The version messages on this page name; null for the next version. */
    val ref: CatalogReleaseRef? get() = segment.ref(tenantKey)

    /** The segment links on this page carry, as it appears in a URL. */
    val segmentValue: String get() = segment.value

    /** Parallel work is open next to the next version, so the page says which draft it works in. */
    val severalDrafts: Boolean get() = drafts.size > 1

    /**
     * The page works in parallel work, not the next version. Shown as a banner, so nobody mistakes it
     * for everyday work, and publishing is not offered: only the next version is published.
     */
    val onParallelWork: Boolean get() = editable && chosen != null && !chosen.isNext

    /** The same page in the next version, for "go to the next version" from parallel work. */
    val workHref: String get() = pageAt(segment.at(null))

    /** The chosen draft's own page, for the bar above a content page. */
    val draftPageHref: String get() = CatalogPages.draft(TenantId(tenantKey), segment)

    /** Parallel work open next to the next version, each linked to this page in that draft. */
    val parallelWork: List<DraftLink> get() = draftLinks.filter { !it.next }

    /** Per published version, the environments it is live in. */
    val liveIn: Map<String, List<String>> get() = releases.associate { requireNotNull(it.version) to it.deployedTo }

    // ---- The catalog page, as people see it (docs/catalog-working-model.md) ----

    /** The page works in the next version, the one people work in 99% of the time. */
    val isNext: Boolean get() = editable && chosen?.isNext == true

    /** What the chosen draft is called: "Next release", or parallel work's name. */
    val draftTitle: String? get() = chosen?.label

    /** The latest published version: what the next version is based on. */
    val latestPublished: String? get() = latestRelease?.version

    /**
     * Every version of the catalog, for the catalog page's Versions list: its drafts when it can be
     * changed here, the next version first, then the published versions, newest first. Each links to
     * its own page.
     */
    val versionRows: List<VersionRow>
        get() = drafts.map { draft ->
            val at = if (draft.isNext) segment.at(null) else segment.at(draft.versionId)
            VersionRow(
                label = draft.label,
                version = null,
                draft = true,
                next = draft.isNext,
                base = draft.base,
                publishedAt = null,
                liveIn = emptyList(),
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
                href = CatalogPages.release(tenantKey, catalogKey, version),
                // The releases of a catalog Epistola supplies come back with every upgrade: nothing to delete.
                offerRemove = !suppliedByEpistola && mayDeleteReleases,
                removeWithheld = !suppliedByEpistola && !mayDeleteReleases,
                removeAction = "/tenants/${tenantKey.value}/catalogs/${catalogKey.value}/releases/$version/delete",
            )
        }

    /**
     * One version on the catalog page: a draft by [label] ("Next release", or parallel work's name), a
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
        val href: String,
        /** Offer removing it from the row: discarding a draft, deleting a published version. */
        val offerRemove: Boolean = false,
        /** Removing it is withheld for want of the role, which the page says once. */
        val removeWithheld: Boolean = false,
        /** Where removing it posts: a draft is discarded at its segment, a published version deleted. */
        val removeAction: String = "",
    )

    /** Some row's removal is withheld, so the Versions page says what it needs. */
    val removalWithheld: Boolean get() = versionRows.any { it.removeWithheld }

    /** Publishing is withheld because the viewer may not publish; the bar says so instead of hiding it. */
    val publishWithheld: Boolean get() = isNext && !mayDiscardDrafts

    /** The chosen draft measured for what it is behind. Measured when a page shows it: it compares the draft with its base. */
    private val measured: CatalogRelease? by lazy {
        chosen?.draftRef?.let { ref -> GetCatalogDraft(tenantKey, catalogKey, ref, withBehind = true).query() }
    }

    /**
     * The releases the chosen draft is behind for the number it would be released as, lowest first,
     * each with what it changed.
     */
    val behind: List<app.epistola.suite.catalog.releases.BehindRelease> get() = measured?.behind.orEmpty()

    /**
     * The releases the chosen draft lacks as its own page and the catalog's page both show them
     * (`catalogs/behind.html`): a sentence for one, a group with a drawing of its line for several.
     */
    val missing: CatalogBehindView by lazy {
        val draft = chosen ?: return@lazy CatalogBehindView.NONE
        CatalogBehindView.of(
            tenantKey = tenantKey,
            catalogKey = catalogKey,
            draftLabel = draft.label,
            isNext = draft.isNext,
            behind = behind,
            line = measured?.behindLine,
            released = releases.mapNotNull { it.version }.toSet(),
        )
    }

    /** Whether to offer starting parallel work. */
    val offerCreateDraft: Boolean get() = editable && mayOpenDrafts && current != null

    /**
     * Whether to offer publishing the chosen draft. Only the next version is published; publishing is
     * catalog-wide — the draft goes out whole — and the dialog says whether it is ready; it needs
     * `CATALOG_RELEASE`.
     */
    val offerRelease: Boolean get() = isNext && mayDiscardDrafts

    /** Whether to offer discarding the chosen draft. */
    val offerDiscard: Boolean get() = editable && mayDiscardDrafts && chosen != null

    /** Starting work is withheld because the viewer may not edit the catalog's content. */
    val draftControlsWithheld: Boolean get() = editable && !mayOpenDrafts

    /** Discarding the chosen draft is withheld because the viewer may not release the catalog. */
    val discardWithheld: Boolean get() = editable && chosen != null && !mayDiscardDrafts

    /** The draft links, the next version first, each to this page in that draft. */
    val draftLinks: List<DraftLink>
        get() = drafts.map { draft ->
            // The next version is linked plainly, parallel work by its id.
            val target = if (draft.isNext) segment.at(null) else segment.at(draft.versionId)
            DraftLink(draft.label, draft.isNext, draft.versionId, draft.base, pageAt(target), active = draft.versionId == chosen?.versionId)
        }

    /** What parallel work can start from: the next version (null), or a release. */
    val createFromOptions: List<String> get() = releases.mapNotNull { it.version }

    private val latestRelease: CatalogRelease? get() = releases.firstOrNull()

    /** A draft to switch to: the [next] version, or parallel work by [label] and [draftId]. */
    data class DraftLink(val label: String, val next: Boolean, val draftId: String, val base: String?, val href: String, val active: Boolean)

    companion object {
        /**
         * The bar for [segment] of an existing catalog, for the current principal; [pageAt] builds the
         * page's URL at another segment. A segment naming a draft the catalog does not have is refused,
         * as a message naming it would be.
         */
        fun of(tenantKey: TenantKey, segment: CatalogSegment, pageAt: (CatalogSegment) -> String): CatalogDraftBar {
            val catalog = GetCatalog(tenantKey, segment.key).query()
            val versions = ListCatalogReleases(tenantKey, segment.key).query()
            // Whether it can be changed here decides it, not where its releases come from (ADR 0032).
            val editability = if (catalog != null) GetCatalogEditability(tenantKey, segment.key).query() else CatalogEditability(null)
            val editable = catalog != null && editability.editable
            val releases = versions.filter { it.status == CatalogReleaseStatus.RELEASED }
            // A read-only catalog offers no drafts; its pages show the release its next version is on.
            val drafts = if (editable) versions.filter { it.status == CatalogReleaseStatus.DRAFT } else emptyList()
            val installed = if (editable) {
                null
            } else {
                val base = versions.firstOrNull { it.isNext }?.base
                releases.firstOrNull { it.version == base } ?: releases.firstOrNull()
            }
            val chosen = when (val draft = segment.draft) {
                null -> drafts.firstOrNull { it.isNext }
                else -> drafts.firstOrNull { it.versionId == draft } ?: throw CatalogReleaseNotFoundException(segment.key, draft)
            }
            val auth = AuthContext.from(SecurityContext.current(), tenantKey)
            val followedBy = chosen?.versionId?.takeIf { editable && chosen.status == CatalogReleaseStatus.DRAFT }?.let { draftId ->
                val following = ListDraftDeployments(tenantKey, catalogKey = segment.key).query().filter { it.draftId == draftId }.map { it.environmentKey }
                if (following.isEmpty()) {
                    emptyList()
                } else {
                    val names = ListEnvironments(TenantId(tenantKey)).query().associate { it.id to it.name }
                    following.map { names[it] ?: it.value }
                }
            }.orEmpty()
            return CatalogDraftBar(
                tenantKey = tenantKey,
                catalogKey = segment.key,
                catalogName = catalog?.name ?: segment.key.value,
                editable = editable,
                segment = segment,
                drafts = drafts,
                releases = releases,
                chosen = chosen,
                mayOpenDrafts = auth.has(Permission.TEMPLATE_EDIT),
                mayDiscardDrafts = auth.has(Permission.CATALOG_RELEASE),
                installed = installed,
                readOnlyReason = editability.reason,
                suppliedByEpistola = catalog?.suppliedByEpistola == true,
                mayDeleteReleases = auth.has(Permission.CATALOG_MANAGE),
                mayDeploy = auth.has(Permission.ENVIRONMENT_DEPLOY),
                followedBy = followedBy,
                pageAt = pageAt,
            )
        }

        /**
         * [pageAt] for a page whose path holds the segment where every per-catalog UI route has it,
         * `/tenants/{tenantId}/catalogs/{catalogId}/…`; the query string is kept.
         */
        fun inPath(path: String, segment: CatalogSegment, query: String? = null): (CatalogSegment) -> String = { target ->
            val parts = path.split('/').toMutableList()
            if (parts.size > CATALOG_PART && parts[CATALOG_PART] == segment.value) parts[CATALOG_PART] = target.value
            parts.joinToString("/") + (query?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: "")
        }

        /** The index of `{catalogId}` in a split UI path: "", "tenants", tenant, "catalogs", catalog. */
        private const val CATALOG_PART = 4
    }
}

/** URLs of the catalog page, at a catalog segment (a draft, or the current version). */
object CatalogPages {
    /**
     * A catalog's list of one resource type (`themes`, `templates`, …), for the catalog [request]
     * names, by its `catalog` parameter or its `{catalogId}` path segment. There is no list across the
     * tenant: without a catalog, the catalog list.
     */
    fun list(request: org.springframework.web.servlet.function.ServerRequest, tenantKey: TenantKey, type: String): String {
        val catalog = request.param("catalog").orElse(null)?.takeIf { it.isNotBlank() } ?: request.pathVariables()["catalogId"]
        return catalog?.let { "/tenants/${tenantKey.value}/catalogs/$it/$type" } ?: "/tenants/${tenantKey.value}/catalogs"
    }

    /** The catalog page: the next version, every version, where each is live. */
    fun browse(tenantId: TenantId, segment: CatalogSegment): String = "/tenants/${tenantId.key}/catalogs/${segment.key.value}/browse"

    /** A draft's own page: the next version at a plain segment, parallel work at `key@<draft id>`. */
    fun draft(tenantId: TenantId, segment: CatalogSegment): String = "/tenants/${tenantId.key}/catalogs/${segment.value}/draft"

    /** The catalog's Releases page: every release and draft, each to its own page. */
    fun releases(tenantId: TenantId, catalogKey: CatalogKey): String = "/tenants/${tenantId.key}/catalogs/${catalogKey.value}/releases"

    /** A published release's own page. */
    fun release(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): String = "/tenants/${tenantKey.value}/catalogs/${catalogKey.value}/releases/$version"
}

/**
 * A fragment of a catalog page (a dialog's body, a status cell) for an HTMX request; opened directly,
 * the page it belongs to instead of a bare fragment: the catalog's, or the catalog list outside one.
 */
internal fun fragmentOrPage(request: org.springframework.web.servlet.function.ServerRequest, view: String, fragment: String, model: Map<String, Any?>): org.springframework.web.servlet.function.ServerResponse = request.htmx {
    fragment(view, fragment) { putAll(model) }
    onFullPage {
        val tenantId = request.tenantId()
        redirect(request.pathVariables()["catalogId"]?.let { CatalogPages.browse(tenantId, CatalogSegment.parse(it).at(null)) } ?: "/tenants/${tenantId.key.value}/catalogs")
    }
}
