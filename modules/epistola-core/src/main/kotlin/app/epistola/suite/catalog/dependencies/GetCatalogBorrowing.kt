// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.releases.AssessDraftNumber
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * What a catalog version takes from other catalogs, made explicit (docs/catalog-workspace-navigation.md).
 *
 * A template can use themes, stencils, images, code lists and fonts of another catalog. Using one
 * declares that catalog ([UsedCatalogDeclarer]), and a draft renders it as the declaration says
 * ([DraftDependencyView]): from the release it pins, or from the draft it names, which releasing
 * releases first. So this answers, per other catalog: whether it is used and by what, what the
 * declaration says, what a pinned release offers, and what a named draft would be released as.
 */
data class GetCatalogBorrowing(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Query<CatalogBorrowing>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * @property catalogs every other catalog this version uses or declares, used ones first.
 * @property others the tenant's other catalogs, which it could use.
 */
data class CatalogBorrowing(val catalogs: List<BorrowedCatalog>, val others: List<OtherCatalog>) {
    /** The catalogs whose named draft publishing here publishes first. */
    val publishedAlong: List<BorrowedCatalog> get() = catalogs.filter { it.publishAlong }

    /**
     * Used but not declared: a catalog upgraded from 1.x, or one the tenant does not have. Releasing
     * refuses it until it is declared on the Dependencies page.
     */
    val undeclared: List<BorrowedCatalog> get() = catalogs.filter { it.used && !it.declared }
}

/**
 * Another catalog as this version sees it.
 *
 * @property release the release its resources render from: the release the declaration pins. Null
 *   when it names a draft instead, or is not declared.
 * @property draftName the draft of it the declaration names, which this version renders live and
 *   releasing releases first; null when it pins a release.
 * @property uses what this version uses of it, in the order found.
 * @property offers what [release] holds of the kinds a template can use.
 */
data class BorrowedCatalog(
    val catalogKey: String,
    val name: String,
    val declared: Boolean,
    val release: String?,
    val uses: List<BorrowedResource>,
    val offers: List<BorrowedResource>,
    /** What the named draft has not published yet, of any kind; empty when it pins a release. */
    val unpublished: List<BorrowedResource> = emptyList(),
    /** The number the named draft would be published as, picked from its changes; null when it has none. */
    val nextNumber: String? = null,
    /** The public id of the named draft, with its name; both null when it pins a release. */
    val draftId: String? = null,
    val draftName: String? = null,
    /** Its latest published version that kept its content, which an editor's preview shows when nothing is unpublished. */
    val latest: String? = null,
    /**
     * What [latest] changed, against [release], in a way that breaks something used here: compared
     * per use, not by version number (docs/workspace-shell.md). A newer version that removes or
     * changes the interface of something nobody here uses still fits.
     */
    val newerBreaks: List<UseBreak> = emptyList(),
) {
    val used: Boolean get() = uses.isNotEmpty()

    /** What this version uses of it that the named draft changed and has not published. */
    val unpublishedUsed: List<BorrowedResource> get() = uses.filter { used -> unpublished.any { it.type == used.type && it.key == used.key } }

    /** The declaration names a draft with something to publish: publishing here publishes it first. */
    val publishAlong: Boolean get() = draftName != null && nextNumber != null

    /** A newer published version than the pinned one, which fits what is used here: upgrading to it is offered. */
    val newerFits: String? get() = newer?.takeIf { newerBreaks.isEmpty() }

    /** A newer published version that does not fit what is used here: this stays on [release], and says why. */
    val newerDoesNotFit: String? get() = newer?.takeIf { newerBreaks.isNotEmpty() }

    private val newer: String? get() = latest?.takeIf { used && release != null && it != release }
}

/** Something used here that a newer version of its catalog breaks, and how, in that catalog's terms. */
data class UseBreak(val type: String, val key: String, val name: String?, val reason: String)

/** A resource of another catalog: its kind (`theme`, `stencil`, `image`, `codeList`, `font`), key and name. */
data class BorrowedResource(val type: String, val key: String, val name: String?)

/** A catalog of the tenant this version does not use, with its latest published version. */
data class OtherCatalog(val catalogKey: String, val name: String, val latestRelease: String?)

@Component
class GetCatalogBorrowingHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val store: CatalogDependencyStore,
    private val compatibility: UseCompatibility,
) : QueryHandler<GetCatalogBorrowing, CatalogBorrowing> {

    override fun handle(query: GetCatalogBorrowing): CatalogBorrowing {
        val content = contentBuilder.build(query.tenantKey, query.catalogKey)
        val own = query.catalogKey.value
        val declared = jdbi.withHandle<List<CatalogDependencyDeclaration>, Exception> { store.declarations(it, query.tenantKey, query.catalogKey) }
        val references = content.dependencies.orEmpty().map { it.toBorrowed() }.filter { it.first != own }
        val catalogs = ListCatalogs(query.tenantKey).query().filter { it.id.value != own }

        val involved = (references.map { it.first } + declared.map { it.catalogKey }).distinct()
        val borrowed = involved.mapNotNull { key ->
            val catalog = catalogs.firstOrNull { it.id.value == key } ?: return@mapNotNull null
            val declaration = declared.firstOrNull { it.catalogKey == key }
            val release = declaration?.releaseVersion
            val offers = release?.let { GetCatalogReleaseDetail(query.tenantKey, catalog.id, it).query() }?.resources.orEmpty()
                .filter { it.resourceType in BORROWABLE }
                .map { BorrowedResource(it.resourceType, it.resourceKey, it.name) }
                .sortedWith(compareBy({ BORROWABLE.indexOf(it.type) }, { it.name?.lowercase() ?: it.key }))
            // Named from the release it renders from; failing that (nothing published, or not in it),
            // from the catalog as it is now, which is where the editor picked it.
            val current by lazy { BrowseCatalog(query.tenantKey, catalog.id).query().resources }
            val uses = references.filter { it.first == key }.map { it.second }.distinct().map { used ->
                val name = offers.firstOrNull { it.type == used.type && it.key == used.key }?.name
                    ?: current.firstOrNull { it.type == used.type && it.slug == used.key }?.name
                used.copy(name = name)
            }
            // A named draft: what it has not published, and the number publishing it along gives it.
            val namedDraft = declaration?.draftId?.let { CatalogReleaseRef(query.tenantKey, catalog.id, DraftRef.Id(it)) }
            val unpublished = namedDraft?.let { ref ->
                GetCatalogResourceChanges(query.tenantKey, catalog.id, ref).query().resources
                    .filter { it.state != CatalogResourceState.RELEASED }
                    .map { change -> BorrowedResource(change.type, change.slug, change.name) }
            }.orEmpty()
            val nextNumber = namedDraft?.takeIf { unpublished.isNotEmpty() }?.let { AssessDraftNumber(query.tenantKey, catalog.id, it.draft).query()?.suggested }
            val latest = latestRelease(query.tenantKey, catalog.id)
            val newerBreaks = if (release != null && latest != null && isAbove(latest, release) && uses.isNotEmpty()) {
                compatibility.breaks(query.tenantKey, catalog.id, release, latest, uses)
            } else {
                emptyList()
            }
            BorrowedCatalog(key, catalog.name, declaration != null, release, uses, offers, unpublished, nextNumber, declaration?.draftId, declaration?.draftName, latest, newerBreaks)
        }.sortedWith(compareBy({ !it.used }, { it.name.lowercase() }))

        val others = catalogs.filter { c -> borrowed.none { it.catalogKey == c.id.value } }
            .map { OtherCatalog(it.id.value, it.name, latestRelease(query.tenantKey, it.id)) }
            .sortedBy { it.name.lowercase() }
        return CatalogBorrowing(borrowed, others)
    }

    private fun isAbove(version: String, than: String): Boolean {
        val a = SemVer.parseOrNull(version) ?: return false
        val b = SemVer.parseOrNull(than) ?: return false
        return a > b
    }

    private fun latestRelease(tenantKey: TenantKey, catalogKey: CatalogKey): String? = ListCatalogReleaseSummaries(tenantKey, catalogKey, limit = 1).query().firstOrNull()?.version

    private fun DependencyRef.toBorrowed(): Pair<String, BorrowedResource> = when (this) {
        is DependencyRef.Theme -> catalogKey to BorrowedResource("theme", key, null)
        is DependencyRef.Stencil -> catalogKey to BorrowedResource("stencil", key, null)
        is DependencyRef.Image -> catalogKey to BorrowedResource("image", key, null)
        is DependencyRef.CodeList -> catalogKey to BorrowedResource("codeList", key, null)
        is DependencyRef.Font -> catalogKey to BorrowedResource("font", key, null)
    }

    private companion object {
        /** The kinds a template can use from another catalog, in the order the page lists them. */
        val BORROWABLE = listOf("stencil", "theme", "image", "font", "codeList")
    }
}
