// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.isNext
import app.epistola.suite.catalog.queries.releaseNumberOrder
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
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
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

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

/**
 * [ListCatalogReleases], one page of it, cut in the database: the drafts and releases are ordered and
 * cut by one statement, and only the page's rows are read in full. Drafts carry no behind releases.
 */
data class ListCatalogReleasePage(
    val catalog: CatalogId,
    val status: CatalogReleaseStatus? = null,
    val page: PageRequest,
) : Query<CatalogReleasePage>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
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
class ListCatalogReleasePageHandler(
    private val jdbi: Jdbi,
    private val versions: CatalogReleaseReader,
) : QueryHandler<ListCatalogReleasePage, CatalogReleasePage> {
    override fun handle(query: ListCatalogReleasePage): CatalogReleasePage = jdbi.withHandle<CatalogReleasePage, Exception> { handle ->
        versions.page(handle, query.catalog, query.status, query.page)
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
 * the released rows of `catalog_versions`. A draft given a number in `numbers` lists the releases it is behind for that
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
        val releases = releases(handle, catalog, ids = null)
        val released = releases.mapNotNull { it.version }
        val lineage = ReleaseLineage.read(handle, catalog)
        val bases = releases.associate { it.version!! to it.base }
        val releasedAt = releases.associate { it.version!! to it.releasedAt }
        val changes = mutableMapOf<String, List<ReleasedChange>>()
        val open = drafts.drafts(handle, catalog).map { draft ->
            val behind = numbers[draft.key]?.let { number -> lineage.behind(draft.base, draft.merged, number) }.orEmpty()
            open(
                draft,
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
            )
        }
        return open + releases.sortedWith(HIGHEST_FIRST)
    }

    /**
     * One page of [all] without behind releases, for [status] or both kinds: the order and the cut are
     * one statement over the catalog's version rows, drafts first, then releases highest first
     * ([releaseNumberOrder]); then only the page's rows are read in full.
     */
    fun page(handle: Handle, catalog: CatalogId, status: CatalogReleaseStatus?, page: PageRequest): CatalogReleasePage {
        val states = when (status) {
            null -> "'draft', 'released'"
            CatalogReleaseStatus.DRAFT -> "'draft'"
            CatalogReleaseStatus.RELEASED -> "'released'"
        }
        val ids = Paging.read(page, RowMapper { rs, _ -> rs.getObject("version_id", UUID::class.java) }) {
            handle.createQuery(
                """
                SELECT v.version_id, ${Paging.TOTAL}
                FROM catalog_versions v
                WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.state IN ($states)
                ORDER BY (v.state = 'draft') DESC, ${isNext("v")} DESC, CASE WHEN v.state = 'draft' THEN v.created_at END,
                         ${releaseNumberOrder("v")}, v.version_id
                ${Paging.LIMIT_OFFSET}
                """,
            )
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
        }
        if (ids.items.isEmpty()) return CatalogReleasePage(emptyList(), ids.total.toInt())
        val onPage = ids.items.toSet()
        val byId = drafts.drafts(handle, catalog).filter { it.versionId in onPage }.associate { it.versionId to open(it) } +
            releaseRows(handle, catalog, ids.items).associate { (id, release) -> id to release }
        return CatalogReleasePage(ids.items.mapNotNull { byId[it] }, ids.total.toInt())
    }

    /** A draft as a version of its catalog. */
    private fun open(draft: CatalogDraft, behind: List<BehindRelease> = emptyList(), behindLine: BehindLine? = null) = CatalogRelease(
        version = null,
        status = CatalogReleaseStatus.DRAFT,
        base = draft.base,
        createdFrom = draft.createdFrom,
        createdAt = draft.createdAt,
        behind = behind,
        behindLine = behindLine,
        versionId = draft.key,
        name = draft.name,
        isNext = draft.isNext,
        revision = draft.revision,
    )

    /** The catalog's releases, or those of [ids], each with the environments it is deployed to. */
    private fun releases(handle: Handle, catalog: CatalogId, ids: Collection<UUID>?): List<CatalogRelease> = releaseRows(handle, catalog, ids).map { it.second }

    private fun releaseRows(handle: Handle, catalog: CatalogId, ids: Collection<UUID>?): List<Pair<UUID, CatalogRelease>> {
        val only = if (ids == null) "" else " AND tv.version_id = ANY(:ids)"
        val deployments = handle.createQuery(
            "SELECT tv.version, d.environment_key FROM environment_catalog_deployments d JOIN catalog_versions tv ON tv.tenant_key = d.tenant_key AND tv.version_id = d.target_version_id AND tv.state = 'released' WHERE d.tenant_key = :t AND d.catalog_id = ${catalogAt("t", "c")}$only ORDER BY d.environment_key",
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .also { q -> ids?.let { q.bind("ids", it.toTypedArray()) } }
            .map { rs, _ -> rs.getString("version") to rs.getString("environment_key") }
            .list()
            .groupBy({ it.first }, { it.second })
        return handle.createQuery(
            """
            SELECT version_id, version, version_key, released_at, notes, fingerprint, content_hash, base_version
            FROM catalog_versions tv WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}$only
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .also { q -> ids?.let { q.bind("ids", it.toTypedArray()) } }
            .map { rs, _ ->
                val version = rs.getString("version")
                rs.getObject("version_id", UUID::class.java) to CatalogRelease(
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
