// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
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
 * The versions of a catalog (contract `listCatalogReleases`): its open drafts, the next version first
 * and then the others, oldest first; then its releases, highest first. [status] narrows to one kind.
 *
 * With [withBehind], each draft lists the releases it is behind for the number it would be released
 * as ([AssessDraftNumber]); that measures each draft's changes, so only pages that show it ask.
 */
data class ListCatalogReleases(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val status: CatalogReleaseStatus? = null,
    val withBehind: Boolean = false,
) : Query<List<CatalogRelease>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** One released version of a catalog, by its number or its id, or null when it has none (contract `getCatalogVersion`). */
data class GetCatalogRelease(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Query<CatalogRelease?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** One draft of a catalog, or null when it has no such draft; [withBehind] as [ListCatalogReleases]. */
data class GetCatalogDraft(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val draft: DraftRef = DraftRef.Next,
    val withBehind: Boolean = false,
) : Query<CatalogRelease?>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogReleasesHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<ListCatalogReleases, List<CatalogRelease>> {
    override fun handle(query: ListCatalogReleases): List<CatalogRelease> {
        val numbers = if (query.withBehind && query.status != CatalogReleaseStatus.RELEASED) versions.numbers(query.tenantKey, query.catalogKey) else emptyMap()
        return jdbi.withHandle<List<CatalogRelease>, Exception> { handle ->
            versions.all(handle, query.tenantKey, query.catalogKey, numbers).filter { query.status == null || it.status == query.status }
        }
    }
}

@Component
class GetCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<GetCatalogRelease, CatalogRelease?> {
    override fun handle(query: GetCatalogRelease): CatalogRelease? = jdbi.withHandle<CatalogRelease?, Exception> { handle ->
        versions.all(handle, query.tenantKey, query.catalogKey).firstOrNull { it.status == CatalogReleaseStatus.RELEASED && (it.version == query.version || it.versionId == query.version) }
    }
}

@Component
class GetCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<GetCatalogDraft, CatalogRelease?> {
    override fun handle(query: GetCatalogDraft): CatalogRelease? {
        val numbers = if (query.withBehind) versions.numbers(query.tenantKey, query.catalogKey, query.draft) else emptyMap()
        return jdbi.withHandle<CatalogRelease?, Exception> { handle ->
            versions.all(handle, query.tenantKey, query.catalogKey, numbers).firstOrNull { version ->
                when (val ref = query.draft) {
                    DraftRef.Next -> version.isNext
                    is DraftRef.Id -> version.status == CatalogReleaseStatus.DRAFT && version.versionId == ref.key
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
class CatalogReleaseReader(
    private val jdbi: Jdbi,
    private val drafts: CatalogDrafts,
    private val assembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler,
    private val fingerprints: app.epistola.suite.catalog.CatalogFingerprintService,
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

    fun all(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, numbers: Map<String, String> = emptyMap()): List<CatalogRelease> {
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
            SELECT version, version_key, released_at, notes, fingerprint, content_hash, base_version
            FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ ->
                val version = rs.getString("version")
                CatalogRelease(
                    version = version,
                    status = CatalogReleaseStatus.RELEASED,
                    versionId = rs.getString("version_key"),
                    base = rs.getString("base_version"),
                    releasedAt = rs.getObject("released_at", OffsetDateTime::class.java),
                    notes = rs.getString("notes"),
                    fingerprint = rs.getString("fingerprint"),
                    contentHash = rs.getString("content_hash"),
                    deployedTo = deployments[version].orEmpty(),
                )
            }
            .list()
        val released = releases.mapNotNull { it.version }
        val lineage = ReleaseLineage.read(handle, tenantKey, catalogKey)
        val bases = releases.associate { it.version!! to it.base }
        val changes = mutableMapOf<String, List<ReleasedChange>>()
        val open = drafts.drafts(handle, tenantKey, catalogKey).map { draft ->
            CatalogRelease(
                version = null,
                status = CatalogReleaseStatus.DRAFT,
                base = draft.base,
                createdFrom = draft.createdFrom,
                createdAt = draft.createdAt,
                behind = numbers[draft.key]?.let { number -> lineage.behind(draft.base, draft.merged, number) }.orEmpty().map { version ->
                    BehindRelease(version, changes.getOrPut(version) { changesOf(tenantKey, catalogKey, version, bases[version], released) })
                },
                versionId = draft.key,
                name = draft.name,
                isNext = draft.isNext,
                revision = draft.revision,
            )
        }
        return open + releases.sortedWith(HIGHEST_FIRST)
    }

    /**
     * What release [version] changed against [base], the release its draft started from. A release
     * that recorded none, or whose base the catalog no longer has, is measured against the release
     * below it, which for releases cut one after another is the same one.
     */
    private fun changesOf(tenantKey: TenantKey, catalogKey: CatalogKey, version: String, base: String?, releases: List<String>): List<ReleasedChange> {
        // Each release's retained content, fingerprinted now by the function every comparison uses, not
        // the digests `release_entries` stored.
        fun of(release: String) = assembler.assemble(tenantKey, catalogKey, release)?.let { fingerprints.resourceFingerprints(it.content) }.orEmpty()
        val own = of(version)
        val below = (base?.takeIf { it in releases } ?: CatalogDrafts.highestBelow(version, releases))?.let(::of).orEmpty()
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
        val HIGHEST_FIRST: Comparator<CatalogRelease> = compareByDescending<CatalogRelease, SemVer?>(nullsFirst()) { it.version?.let(SemVer::parseOrNull) }
            .thenByDescending { it.version }
    }
}
