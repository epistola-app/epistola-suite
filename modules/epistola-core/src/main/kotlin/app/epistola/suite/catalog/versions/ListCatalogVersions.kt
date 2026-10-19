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
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * The versions of a catalog (contract `listCatalogVersions`): its open drafts, the next version first
 * and then the others, oldest first; then its releases, highest first. [status] narrows to one kind.
 *
 * With [withBehind], each draft lists the releases it is behind for the number it would be released
 * as ([AssessDraftNumber]); that measures each draft's changes, so only pages that show it ask.
 */
data class ListCatalogVersions(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val status: CatalogVersionStatus? = null,
    val withBehind: Boolean = false,
) : Query<List<CatalogVersion>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** One released version of a catalog, by its number or its id, or null when it has none (contract `getCatalogVersion`). */
data class GetCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<CatalogVersion?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** One draft of a catalog, or null when it has no such draft; [withBehind] as [ListCatalogVersions]. */
data class GetCatalogDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val draft: DraftRef = DraftRef.Next,
    val withBehind: Boolean = false,
) : Query<CatalogVersion?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogVersionsHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
) : QueryHandler<ListCatalogVersions, List<CatalogVersion>> {
    override fun handle(query: ListCatalogVersions): List<CatalogVersion> {
        val numbers = if (query.withBehind && query.status != CatalogVersionStatus.RELEASED) versions.numbers(query.tenantKey, query.catalogKey) else emptyMap()
        return jdbi.withHandle<List<CatalogVersion>, Exception> { handle ->
            versions.all(handle, query.tenantKey, query.catalogKey, numbers).filter { query.status == null || it.status == query.status }
        }
    }
}

@Component
class GetCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
) : QueryHandler<GetCatalogVersion, CatalogVersion?> {
    override fun handle(query: GetCatalogVersion): CatalogVersion? = jdbi.withHandle<CatalogVersion?, Exception> { handle ->
        versions.all(handle, query.tenantKey, query.catalogKey).firstOrNull { it.status == CatalogVersionStatus.RELEASED && (it.version == query.version || it.versionId == query.version) }
    }
}

@Component
class GetCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogVersionReader,
) : QueryHandler<GetCatalogDraft, CatalogVersion?> {
    override fun handle(query: GetCatalogDraft): CatalogVersion? {
        val numbers = if (query.withBehind) versions.numbers(query.tenantKey, query.catalogKey, query.draft) else emptyMap()
        return jdbi.withHandle<CatalogVersion?, Exception> { handle ->
            versions.all(handle, query.tenantKey, query.catalogKey, numbers).firstOrNull { version ->
                when (val ref = query.draft) {
                    DraftRef.Next -> version.isNext
                    is DraftRef.Id -> version.status == CatalogVersionStatus.DRAFT && version.versionId == ref.key
                }
            }
        }
    }
}

/**
 * Reads a catalog's versions: its open drafts from `catalog_versions`, the rest from
 * `catalog_releases`. A draft given a number in `numbers` lists the releases it is behind for that
 * number, each with what that release changed.
 */
@Component
class CatalogVersionReader(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val releaseEntries: ReleaseEntryStore,
) {
    /**
     * Per draft key, the number it would be released as now ([AssessDraftNumber]): [draft] only, or
     * every open draft. Outside any handle, since measuring a draft builds its content.
     */
    fun numbers(tenantKey: TenantKey, catalogKey: CatalogKey, draft: DraftRef? = null): Map<String, String> {
        val open = jdbi.withHandle<List<CatalogDraft>, Exception> { drafts.drafts(it, tenantKey, catalogKey) }
            .filter { d ->
                when (draft) {
                    null -> true
                    DraftRef.Next -> d.isNext
                    is DraftRef.Id -> d.key == draft.key
                }
            }
        return open.mapNotNull { d -> AssessDraftNumber(tenantKey, catalogKey, d.ref).query()?.let { d.key to it.suggested } }.toMap()
    }

    fun all(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, numbers: Map<String, String> = emptyMap()): List<CatalogVersion> {
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
            SELECT version, version_key, released_at, notes, fingerprint, base_version
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
                    versionId = rs.getString("version_key"),
                    base = rs.getString("base_version"),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    deployedTo = deployments[version].orEmpty(),
                )
            }
            .list()
        val released = releases.mapNotNull { it.version }
        val publishedAt = releases.map { it.version!! to it.releasedAt }
        val bases = releases.associate { it.version!! to it.base }
        val changes = mutableMapOf<String, List<ReleasedChange>>()
        val open = drafts.drafts(handle, tenantKey, catalogKey).map { draft ->
            CatalogVersion(
                version = null,
                status = CatalogVersionStatus.DRAFT,
                base = draft.base,
                createdFrom = draft.createdFrom,
                createdAt = draft.createdAt,
                behind = numbers[draft.key]?.let { number -> CatalogDrafts.behind(draft.base, draft.createdAt, number, publishedAt) }.orEmpty().map { version ->
                    BehindRelease(version, changes.getOrPut(version) { changesOf(handle, tenantKey, catalogKey, version, bases[version], released) })
                },
                versionId = draft.key,
                name = draft.name,
                isNext = draft.isNext,
            )
        }
        return open + releases.sortedWith(HIGHEST_FIRST)
    }

    /**
     * What release [version] changed against [base], the release its draft started from. A release
     * that recorded none, or whose base the catalog no longer has, is measured against the release
     * below it, which for releases cut one after another is the same one.
     */
    private fun changesOf(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, base: String?, releases: List<String>): List<ReleasedChange> {
        val own = releaseEntries.entriesOf(handle, tenantKey, catalogKey, version).associate { it.contentKey to it.fingerprint }
        val below = (base?.takeIf { it in releases } ?: CatalogDrafts.highestBelow(version, releases))
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
        val HIGHEST_FIRST: Comparator<CatalogVersion> = compareByDescending<CatalogVersion, SemVer?>(nullsFirst()) { it.version?.let(SemVer::parseOrNull) }
            .thenByDescending { it.version }
    }
}
