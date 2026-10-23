// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogNamesApplier
import app.epistola.suite.catalog.address
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.releases.CatalogRelease
import app.epistola.suite.catalog.releases.CatalogReleaseReader
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Where every catalog of a tenant stands (docs/catalog-workspace-navigation.md, landing): for each,
 * its open drafts and what they are behind, its latest release, how much of its current draft waits
 * to be marked ready, and which of its releases each environment serves. The landing page of a
 * tenant, so someone sees what is going on before choosing a catalog version to work in.
 *
 * The catalogs that can be changed here first, then the read-only ones, each by name.
 */
data class GetCatalogOverview(
    override val tenantKey: TenantKey,
) : Query<List<CatalogOverview>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * @property drafts the open drafts, highest first; the first is the catalog's current version.
 * @property latestRelease the highest release; for a catalog that cannot be changed here, the one its
 *   content is on (its next version's base), which is the highest unless it has changes of its own.
 * @property readOnlyReason why the catalog cannot be changed here; null when it can (ADR 0032).
 * @property notReady how many resources of the current draft wait to be marked ready; null for a
 *   catalog without a draft.
 * @property served per environment, the releases it serves, highest first; environments serving
 *   none of this catalog are left out.
 */
data class CatalogOverview(
    val catalogKey: CatalogKey,
    val name: String,
    val namespace: String,
    val drafts: List<DraftOverview>,
    val latestRelease: ReleaseOverview?,
    val notReady: Int?,
    val served: Map<String, List<String>>,
    val readOnlyReason: CatalogReadOnlyReason? = null,
    /** Another catalog of the tenant has the same key (ADR 0034), so this one is named by namespace too. */
    val sharesKey: Boolean = false,
) {
    /** How its pages address it: its key, or `namespace/key` when another catalog has the same key. */
    val segment: String get() = if (sharesKey) "$namespace/${catalogKey.value}" else catalogKey.value

    /** Whether the catalog can be changed here: it has drafts to work in, and releases them. */
    val editable: Boolean get() = readOnlyReason == null

    /** Its next version, where everyday work goes; null for a catalog that cannot be changed here, or one without. */
    val next: DraftOverview? get() = drafts.firstOrNull { it.isNext }
}

/**
 * An open draft: the next version, or parallel work by [label]; and the releases it does not
 * contain that are above its base.
 */
data class DraftOverview(val draftId: String, val label: String, val isNext: Boolean, val base: String?, val behind: List<String>)

data class ReleaseOverview(val version: String, val releasedAt: OffsetDateTime?)

@Component
class GetCatalogOverviewHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
    private val readyMarks: ReadyMarks,
    private val catalogNames: CatalogNamesApplier,
) : QueryHandler<GetCatalogOverview, List<CatalogOverview>> {
    override fun handle(query: GetCatalogOverview): List<CatalogOverview> {
        val editability = ListCatalogEditability(query.tenantKey).query()
        val catalogs = ListCatalogs(query.tenantKey).query()
            .sortedWith(compareBy({ !editability.editable(it.address) }, { it.name.lowercase() }))
        val shared = catalogs.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        // Each catalog read in its namespace: two can share a key (ADR 0034, phase 4).
        return catalogs.map { catalog ->
            catalogNames.named(catalog.id, catalog.namespace) {
                jdbi.withHandle<CatalogOverview, Exception> { handle -> overview(handle, query.tenantKey, catalog, editability.of(catalog.address)?.reason).copy(sharesKey = catalog.id in shared) }
            }
        }
    }

    private fun overview(handle: Handle, tenantKey: TenantKey, catalog: Catalog, reason: CatalogReadOnlyReason?): CatalogOverview {
        val all = versions.all(handle, tenantKey, catalog.id)
        // A catalog that cannot be changed here has a next version only to hold what it
        // received (ADR 0032): there is no draft for anyone to work in.
        val editable = reason == null
        val drafts = if (editable) all.filter { it.status == CatalogReleaseStatus.DRAFT } else emptyList()
        val releases = all.filter { it.status == CatalogReleaseStatus.RELEASED }
        val base = all.firstOrNull { it.isNext }?.base?.takeUnless { editable }
        return CatalogOverview(
            catalogKey = catalog.id,
            name = catalog.name,
            namespace = catalog.namespace,
            drafts = drafts.map { DraftOverview(it.versionId, it.label, it.isNext, it.base, it.behind.map { behind -> behind.version }) },
            latestRelease = (base?.let { b -> releases.firstOrNull { it.version == b } } ?: releases.firstOrNull())
                ?.let { ReleaseOverview(requireNotNull(it.version), it.releasedAt) },
            // The current version's readiness: the next version's.
            notReady = if (drafts.isEmpty()) null else readyMarks.readiness(handle, tenantKey, catalog.id).count { !it.value },
            served = servedBy(releases),
            readOnlyReason = reason,
        )
    }

    private fun servedBy(releases: List<CatalogRelease>): Map<String, List<String>> = releases
        .flatMap { release -> release.deployedTo.map { environment -> environment to requireNotNull(release.version) } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
}
