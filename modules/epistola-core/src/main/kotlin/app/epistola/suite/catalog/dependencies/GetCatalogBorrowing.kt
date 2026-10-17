// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogRelease
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.versions.AssessDraftNumber
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
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
 * A template can use themes, stencils, images, code lists and fonts of another catalog. The editor
 * offers every catalog's, but a release renders them from the release of that catalog its declaration
 * is locked to, and releasing refuses a reference to a catalog that is not declared. So this answers,
 * per other catalog: whether it is used and by what, whether it is declared, which release it renders
 * from (or would, once declared: the highest), and what that release offers.
 */
data class GetCatalogBorrowing(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Query<CatalogBorrowing>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * @property catalogs every other catalog this version uses or declares, used ones first.
 * @property others the tenant's other catalogs, which it could use.
 */
data class CatalogBorrowing(val catalogs: List<BorrowedCatalog>, val others: List<OtherCatalog>) {
    /**
     * The catalogs whose use here differs from what publishing would tie to: what the preview shows
     * is not what would be published (docs/catalog-working-model.md, rule 7).
     */
    val outOfStep: List<BorrowedCatalog> get() = catalogs.filter { it.publishAlong || it.retieTo != null }

    /** Used but not declared: publishing has to declare them first. */
    val undeclared: List<BorrowedCatalog> get() = catalogs.filter { it.used && !it.declared }

    /** Used, not declared, and without a published version to declare: publishing cannot go ahead. */
    val unavailable: List<BorrowedCatalog> get() = undeclared.filter { it.release == null }
}

/**
 * Another catalog as this version sees it.
 *
 * @property release the release its resources render from: the declaration's lock, or for an
 *   undeclared catalog the highest release, which declaring it locks to. Null when there is none.
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
    /** Everything its next version has not published yet, of any kind; empty for a subscribed catalog. */
    val unpublished: List<BorrowedResource> = emptyList(),
    /** The number its next version would be published as, picked from its changes; null without one. */
    val nextNumber: String? = null,
    /** Its latest published version that kept its content, which an editor's preview shows when nothing is unpublished. */
    val latest: String? = null,
) {
    val used: Boolean get() = uses.isNotEmpty()

    /** What this version uses of it that its next version changed and has not published: the preview shows it, a publish would not. */
    val unpublishedUsed: List<BorrowedResource> get() = uses.filter { used -> unpublished.any { it.type == used.type && it.key == used.key } }

    /** Used here, but in no published version of it: a publish here needs it published first. */
    val onlyInWork: Boolean get() = uses.any { used -> offers.none { it.type == used.type && it.key == used.key } } && nextNumber != null

    /** Publishing here should publish its next version along, so what is published is what was previewed. */
    val publishAlong: Boolean get() = used && nextNumber != null && (unpublishedUsed.isNotEmpty() || onlyInWork)

    /**
     * Nothing to publish along, but a newer published version than the one this is tied to, which the
     * preview shows: publishing here would tie to the older one unless it is re-tied.
     */
    val retieTo: String? get() = latest?.takeIf { used && declared && !publishAlong && it != release }
}

/** A resource of another catalog: its kind (`theme`, `stencil`, `image`, `codeList`, `font`), key and name. */
data class BorrowedResource(val type: String, val key: String, val name: String?)

/** A catalog of the tenant this version does not use, with its latest published version. */
data class OtherCatalog(val catalogKey: String, val name: String, val latestRelease: String?)

@Component
class GetCatalogBorrowingHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val store: CatalogDependencyStore,
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
            val release = declaration?.releaseVersion ?: latestRetained(query.tenantKey, catalog.id).takeIf { declaration == null }
            val offers = release?.let { GetCatalogRelease(query.tenantKey, catalog.id, it).query() }?.resources.orEmpty()
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
            val authoredWork = if (catalog.type == CatalogType.AUTHORED) {
                ListCatalogVersions(query.tenantKey, catalog.id, CatalogVersionStatus.DRAFT).query().firstOrNull()
            } else {
                null
            }
            val unpublished = authoredWork?.let {
                GetCatalogResourceChanges(query.tenantKey, catalog.id).query().resources
                    .filter { it.state != CatalogResourceState.RELEASED }
                    .map { change -> BorrowedResource(change.type, change.slug, change.name) }
            }.orEmpty()
            val nextNumber = authoredWork?.takeIf { unpublished.isNotEmpty() }?.let { work ->
                AssessDraftNumber(query.tenantKey, catalog.id, work.version).query()?.takeIf { it.tooLow }?.suggested ?: work.version
            }
            BorrowedCatalog(key, catalog.name, declaration != null, release, uses, offers, unpublished, nextNumber, latestRetained(query.tenantKey, catalog.id))
        }.sortedWith(compareBy({ !it.used }, { it.name.lowercase() }))

        val others = catalogs.filter { c -> borrowed.none { it.catalogKey == c.id.value } }
            .map { OtherCatalog(it.id.value, it.name, latestRetained(query.tenantKey, it.id)) }
            .sortedBy { it.name.lowercase() }
        return CatalogBorrowing(borrowed, others)
    }

    private fun latestRetained(tenantKey: TenantKey, catalogKey: CatalogKey): String? = ListCatalogReleases(tenantKey, catalogKey).query().firstOrNull { it.retained }?.version

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
