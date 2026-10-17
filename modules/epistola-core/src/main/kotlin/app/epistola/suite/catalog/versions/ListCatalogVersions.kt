// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * The versions of a catalog, highest first (contract `listCatalogVersions`): its open draft and its
 * releases. [status] narrows to one kind.
 */
data class ListCatalogVersions(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val status: CatalogVersionStatus? = null,
) : Query<List<CatalogVersion>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** One version of a catalog, or null when it has none with that number (contract `getCatalogVersion`). */
data class GetCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<CatalogVersion?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogVersionsHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
) : QueryHandler<ListCatalogVersions, List<CatalogVersion>> {
    override fun handle(query: ListCatalogVersions): List<CatalogVersion> = jdbi.withHandle<List<CatalogVersion>, Exception> { handle ->
        versions.all(handle, query.tenantKey, query.catalogKey).filter { query.status == null || it.status == query.status }
    }
}

@Component
class GetCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
) : QueryHandler<GetCatalogVersion, CatalogVersion?> {
    override fun handle(query: GetCatalogVersion): CatalogVersion? = jdbi.withHandle<CatalogVersion?, Exception> { handle ->
        versions.all(handle, query.tenantKey, query.catalogKey).firstOrNull { it.version == query.version }
    }
}

/** Reads a catalog's versions: the open draft from `catalog_drafts`, the rest from `catalog_releases`. */
@Component
class CatalogVersionReader(
    private val drafts: CatalogDrafts,
) {
    fun all(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogVersion> {
        val draft = drafts.draft(handle, tenantKey, catalogKey)?.let {
            CatalogVersion(it.version, CatalogVersionStatus.DRAFT, base = it.base, createdFrom = it.createdFrom, createdAt = it.createdAt)
        }
        val deployments = handle.createQuery(
            "SELECT version, environment_key FROM environment_catalog_deployments WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} ORDER BY environment_key",
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ -> rs.getString("version") to rs.getString("environment_key") }
            .list()
            .groupBy({ it.first }, { it.second })
        val releases = handle.createQuery(
            """
            SELECT version, released_at, notes, fingerprint, content_retained
            FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ ->
                val version = rs.getString("version")
                CatalogVersion(
                    version = version,
                    status = CatalogVersionStatus.RELEASED,
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    contentRetained = rs.getBoolean("content_retained"),
                    deployedTo = deployments[version].orEmpty(),
                )
            }
            .list()
        return (listOfNotNull(draft) + releases).sortedWith(HIGHEST_FIRST)
    }

    private companion object {
        /** SemVer order, highest first; a version that is not SemVer (imported before 2.0) last. */
        val HIGHEST_FIRST: Comparator<CatalogVersion> = compareByDescending<CatalogVersion, SemVer?>(nullsFirst()) { SemVer.parseOrNull(it.version) }
            .thenByDescending { it.version }
    }
}
