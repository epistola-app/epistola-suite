// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.ready.ReadyMarks
import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionReader
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Where every catalog of a tenant stands (docs/catalog-workspace-navigation.md, landing): for each,
 * its open drafts and what they are behind, its latest release, how much of its current draft waits
 * to be marked ready, and which of its releases each environment serves. The landing page of a
 * tenant, so someone sees what is going on before choosing a catalog version to work in.
 *
 * Authored catalogs first, then subscribed ones, each by name.
 */
data class GetCatalogOverview(
    override val tenantKey: TenantKey,
) : Query<List<CatalogOverview>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * @property drafts the open drafts, highest first; the first is the catalog's current version.
 * @property latestRelease the highest release; for a subscribed catalog, its installed release.
 * @property notReady how many resources of the current draft wait to be marked ready; null for a
 *   catalog without a draft.
 * @property served per environment, the releases it serves, highest first; environments serving
 *   none of this catalog are left out.
 */
data class CatalogOverview(
    val catalogKey: CatalogKey,
    val name: String,
    val namespace: String,
    val type: CatalogType,
    val drafts: List<DraftOverview>,
    val latestRelease: ReleaseOverview?,
    val notReady: Int?,
    val served: Map<String, List<String>>,
) {
    val authored: Boolean get() = type == CatalogType.AUTHORED

    /** The version a page opened from here works in: the highest draft, else the latest release. */
    val current: String? get() = drafts.firstOrNull()?.version ?: latestRelease?.version
}

/** An open draft, and the releases it does not contain that are above its base. */
data class DraftOverview(val version: String, val base: String?, val behind: List<String>)

data class ReleaseOverview(val version: String, val releasedAt: OffsetDateTime?)

@Component
class GetCatalogOverviewHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
    private val readyMarks: ReadyMarks,
) : QueryHandler<GetCatalogOverview, List<CatalogOverview>> {
    override fun handle(query: GetCatalogOverview): List<CatalogOverview> {
        val catalogs = ListCatalogs(query.tenantKey).query()
            .sortedWith(compareBy({ it.type != CatalogType.AUTHORED }, { it.name.lowercase() }))
        return jdbi.withHandle<List<CatalogOverview>, Exception> { handle ->
            catalogs.map { catalog ->
                val all = versions.all(handle, query.tenantKey, catalog.id)
                val drafts = all.filter { it.status == CatalogVersionStatus.DRAFT }
                val releases = all.filter { it.status == CatalogVersionStatus.RELEASED }
                CatalogOverview(
                    catalogKey = catalog.id,
                    name = catalog.name,
                    namespace = catalog.namespace,
                    type = catalog.type,
                    drafts = drafts.map { DraftOverview(it.version, it.base, it.behind.map { behind -> behind.version }) },
                    latestRelease = (catalog.installedReleaseVersion?.let { installed -> releases.firstOrNull { it.version == installed } } ?: releases.firstOrNull())
                        ?.let { ReleaseOverview(it.version, it.releasedAt) },
                    // The current version's readiness: the highest draft's.
                    notReady = if (drafts.isEmpty()) null else readyMarks.readiness(handle, query.tenantKey, catalog.id).count { !it.value },
                    served = servedBy(releases),
                )
            }
        }
    }

    private fun servedBy(releases: List<CatalogVersion>): Map<String, List<String>> = releases
        .flatMap { release -> release.deployedTo.map { environment -> environment to release.version } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
}
