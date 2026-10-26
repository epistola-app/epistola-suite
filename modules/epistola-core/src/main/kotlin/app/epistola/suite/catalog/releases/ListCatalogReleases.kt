// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
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
    val catalog: CatalogId,
    val status: CatalogReleaseStatus? = null,
    val withBehind: Boolean = false,
) : Query<List<CatalogRelease>>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/** One released version of a catalog, by its number or its id, or null when it has none (contract `getCatalogVersion`). */
data class GetCatalogRelease(
    val catalog: CatalogId,
    val version: String,
) : Query<CatalogRelease?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

/** One draft of a catalog, or null when it has no such draft; [withBehind] as [ListCatalogReleases]. */
data class GetCatalogDraft(
    val catalog: CatalogId,
    val draft: DraftRef = DraftRef.Next,
    val withBehind: Boolean = false,
) : Query<CatalogRelease?>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogReleasesHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<ListCatalogReleases, List<CatalogRelease>> {
    override fun handle(query: ListCatalogReleases): List<CatalogRelease> {
        val numbers = if (query.withBehind && query.status != CatalogReleaseStatus.RELEASED) versions.numbers(query.catalog) else emptyMap()
        return jdbi.withHandle<List<CatalogRelease>, Exception> { handle ->
            versions.all(handle, query.catalog, numbers).filter { query.status == null || it.status == query.status }
        }
    }
}

@Component
class GetCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<GetCatalogRelease, CatalogRelease?> {
    override fun handle(query: GetCatalogRelease): CatalogRelease? = jdbi.withHandle<CatalogRelease?, Exception> { handle ->
        versions.all(handle, query.catalog).firstOrNull { it.status == CatalogReleaseStatus.RELEASED && (it.version == query.version || it.versionId == query.version) }
    }
}

@Component
class GetCatalogDraftHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<GetCatalogDraft, CatalogRelease?> {
    override fun handle(query: GetCatalogDraft): CatalogRelease? {
        val numbers = if (query.withBehind) versions.numbers(query.catalog, query.draft) else emptyMap()
        return jdbi.withHandle<CatalogRelease?, Exception> { handle ->
            versions.all(handle, query.catalog, numbers).firstOrNull { version ->
                when (val ref = query.draft) {
                    DraftRef.Next -> version.isNext
                    is DraftRef.Id -> version.status == CatalogReleaseStatus.DRAFT && version.versionId == ref.key
                    is DraftRef.Released -> version.status == CatalogReleaseStatus.RELEASED && version.version == ref.version
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
    fun numbers(catalog: CatalogId, draft: DraftRef? = null): Map<String, String> {
        val open = jdbi.withHandle<List<CatalogDraft>, Exception> { drafts.drafts(it, catalog) }
            .filter { d ->
                when (draft) {
                    null -> true
                    DraftRef.Next -> d.isNext
                    is DraftRef.Id -> d.key == draft.key
                    is DraftRef.Released -> false
                }
            }
        return open.mapNotNull { d -> AssessDraftNumber(catalog, d.ref).query()?.let { d.key to it.suggested } }.toMap()
    }

    fun all(handle: Handle, catalog: CatalogId, numbers: Map<String, String> = emptyMap()): List<CatalogRelease> {
        val deployments = handle.createQuery(
            "SELECT tv.version, d.environment_key FROM environment_catalog_deployments d JOIN catalog_versions tv ON tv.tenant_key = d.tenant_key AND tv.version_id = d.target_version_id AND tv.state = 'released' WHERE d.tenant_key = :t AND d.catalog_id = ${catalogAt("t", "c")} ORDER BY d.environment_key",
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> rs.getString("version") to rs.getString("environment_key") }
            .list()
            .groupBy({ it.first }, { it.second })
        val releases = handle.createQuery(
            """
            SELECT version, version_key, released_at, notes, fingerprint, content_hash, base_version
            FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
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
        val lineage = ReleaseLineage.read(handle, catalog)
        val bases = releases.associate { it.version!! to it.base }
        val releasedAt = releases.associate { it.version!! to it.releasedAt }
        val changes = mutableMapOf<String, List<ReleasedChange>>()
        val open = drafts.drafts(handle, catalog).map { draft ->
            val behind = numbers[draft.key]?.let { number -> lineage.behind(draft.base, draft.merged, number) }.orEmpty()
            CatalogRelease(
                version = null,
                status = CatalogReleaseStatus.DRAFT,
                base = draft.base,
                createdFrom = draft.createdFrom,
                createdAt = draft.createdAt,
                behind = behind.map { version ->
                    BehindRelease(
                        version = version,
                        changes = changes.getOrPut(version) { changesOf(handle, catalog, version, bases[version], released) },
                        // As its line names it: a base named by its fingerprint is the release here that has it.
                        base = lineage.releases[version]?.base?.version,
                        releasedAt = releasedAt[version],
                    )
                },
                behindLine = behind.takeIf { it.isNotEmpty() }?.let { lineage.behindLine(draft.base, draft.merged, it) },
                versionId = draft.key,
                name = draft.name,
                isNext = draft.isNext,
                revision = draft.revision,
            )
        }
        return open + releases.sortedWith(HIGHEST_FIRST)
    }

    /**
     * What release [version] changed against [base], the release its draft started from, each by the
     * name the release gives it (a removed one by its base's). A release that recorded none, or whose
     * base the catalog no longer has, is measured against the release below it, which for releases
     * cut one after another is the same one. Read on [handle], so a page measuring several releases
     * holds one connection, not two.
     */
    private fun changesOf(handle: Handle, catalog: CatalogId, version: String, base: String?, releases: List<String>): List<ReleasedChange> {
        // Each release's retained content, fingerprinted now by the function every comparison uses, not
        // the digests `release_resources` stored.
        fun of(release: String) = assembler.assemble(handle, catalog, release)?.content
        val ownContent = of(version)
        val belowContent = (base?.takeIf { it in releases } ?: CatalogDrafts.highestBelow(version, releases))?.let(::of)
        val own = ownContent?.let { fingerprints.resourceFingerprints(it) }.orEmpty()
        val below = belowContent?.let { fingerprints.resourceFingerprints(it) }.orEmpty()
        val names = (belowContent?.resourceEntries.orEmpty() + ownContent?.resourceEntries.orEmpty()).associate { "${it.type}/${it.key}" to it.name }
        return (own.keys + below.keys).sorted().mapNotNull { key ->
            val change = when {
                key !in below -> "added"
                key !in own -> "removed"
                own[key] != below[key] -> "modified"
                else -> null
            }
            change?.let { ReleasedChange(key.substringBefore('/'), key.substringAfter('/'), it, names[key]) }
        }
    }

    private companion object {
        /** SemVer order, highest first; a version that is not SemVer (imported before 2.0) last. */
        val HIGHEST_FIRST: Comparator<CatalogRelease> = compareByDescending<CatalogRelease, SemVer?>(nullsFirst()) { it.version?.let(SemVer::parseOrNull) }
            .thenByDescending { it.version }
    }
}
