// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.common.ids.CatalogKey
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
 * The versions of a catalog, highest first (contract `listCatalogVersions`): its open drafts and its
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

/**
 * Reads a catalog's versions: its open drafts from `catalog_versions`, the rest from
 * `catalog_releases`. A draft lists the releases it is behind, each with what that release changed.
 */
@Component
class CatalogVersionReader(
    private val drafts: CatalogDrafts,
    private val releaseEntries: ReleaseEntryStore,
) {
    fun all(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogVersion> {
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
        val released = releases.map { it.version }
        val changes = mutableMapOf<String, List<ReleasedChange>>()
        val open = drafts.drafts(handle, tenantKey, catalogKey).map { draft ->
            CatalogVersion(
                draft.version,
                CatalogVersionStatus.DRAFT,
                base = draft.base,
                createdFrom = draft.createdFrom,
                createdAt = draft.createdAt,
                behind = CatalogDrafts.behind(draft.version, draft.base, released).map { version ->
                    BehindRelease(version, changes.getOrPut(version) { changesOf(handle, tenantKey, catalogKey, version, released) })
                },
            )
        }
        return (open + releases).sortedWith(HIGHEST_FIRST)
    }

    /**
     * What release [version] changed against the release below it: the base it was cut on is not
     * recorded for a release, and for releases cut one after another that is the one below it.
     */
    private fun changesOf(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, releases: List<String>): List<ReleasedChange> {
        val own = releaseEntries.entriesOf(handle, tenantKey, catalogKey, version).associate { it.contentKey to it.fingerprint }
        val below = CatalogDrafts.highestBelow(version, releases)
            ?.let { previous -> releaseEntries.entriesOf(handle, tenantKey, catalogKey, previous).associate { it.contentKey to it.fingerprint } }
            .orEmpty()
        return (own.keys + below.keys).sorted().mapNotNull { key ->
            val change = when {
                key !in below -> "added"
                key !in own -> "removed"
                own[key] != below[key] -> "modified"
                else -> null
            }
            change?.let { ReleasedChange(key.substringBefore('/'), key.substringAfter('/'), it) }
        }
    }

    private companion object {
        /** SemVer order, highest first; a version that is not SemVer (imported before 2.0) last. */
        val HIGHEST_FIRST: Comparator<CatalogVersion> = compareByDescending<CatalogVersion, SemVer?>(nullsFirst()) { SemVer.parseOrNull(it.version) }
            .thenByDescending { it.version }
    }
}
