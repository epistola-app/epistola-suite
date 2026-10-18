// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Whether a catalog version can still be edited (contract ADR 0002). */
enum class CatalogVersionStatus { DRAFT, RELEASED }

/**
 * One version of a catalog: an open draft, or a release (contract ADR 0002, amendment 2).
 *
 * A draft has no number until it is released: [version] is null for one, and it is named by
 * [draftId] instead. A catalog's [isNext] draft is its next version, the one everyday work goes into;
 * any other is long-term work, with a [name].
 *
 * @property base the released version a draft started from; for a release, the one its draft started
 *   from. Null for a draft of a catalog that was never released, a catalog's first release, a release
 *   installed from elsewhere, and a release cut before releases recorded it (`V20261017144436`).
 * @property createdFrom for a draft, the released version it was copied from when that is not its
 *   base, or [CatalogDraft.FROM_NEXT] when it was copied from the next version.
 * @property behind for a draft, the released versions above its base, lowest first: it does not
 *   contain their changes unless an author applied them by hand.
 * @property deployedTo the environments that deploy a released version.
 */
data class CatalogVersion(
    val version: String?,
    val status: CatalogVersionStatus,
    val base: String? = null,
    val createdFrom: String? = null,
    val createdAt: OffsetDateTime? = null,
    val releasedAt: OffsetDateTime? = null,
    val notes: String? = null,
    val fingerprint: String? = null,
    val contentRetained: Boolean? = null,
    val deployedTo: List<String> = emptyList(),
    val behind: List<BehindRelease> = emptyList(),
    val draftId: UUID? = null,
    val name: String? = null,
    val isNext: Boolean = false,
) {
    /** What people call it: "Next version", long-term work's name, or a release's number. */
    val label: String get() = when {
        status == CatalogVersionStatus.RELEASED -> "v$version"
        isNext -> CatalogDraft.NEXT_LABEL
        else -> name ?: CatalogDraft.NEXT_LABEL
    }

    /** How a message names this draft; null for a release. */
    val draftRef: DraftRef? get() = when {
        status == CatalogVersionStatus.RELEASED -> null
        isNext -> DraftRef.Next
        else -> draftId?.let(DraftRef::Id)
    }
}

/**
 * A released version a draft is behind, and what that release changed against its own base (the
 * release below it when it recorded none).
 */
data class BehindRelease(val version: String, val changes: List<ReleasedChange>)

/** A resource a release added, modified or removed. [change] is `added`, `modified` or `removed`. */
data class ReleasedChange(val type: String, val key: String, val change: String)

/**
 * An open draft of an authored catalog. [versionId] is what its content rows belong to, and its id.
 * The [isNext] one is the catalog's next version; any other is long-term work, with a [name].
 */
data class CatalogDraft(
    val versionId: UUID,
    val isNext: Boolean,
    val name: String?,
    val base: String?,
    val createdFrom: String?,
    val createdAt: OffsetDateTime,
) {
    val label: String get() = if (isNext) NEXT_LABEL else name ?: NEXT_LABEL

    val ref: DraftRef get() = if (isNext) DraftRef.Next else DraftRef.Id(versionId)

    companion object {
        /** What the next version is always called: it has no name of its own (ADR 0002, amendment 2). */
        const val NEXT_LABEL: String = "Next version"

        /** [createdFrom] of a draft copied from the next version, unreleased changes included. */
        const val FROM_NEXT: String = "next"
    }
}

/**
 * The SQL of catalog drafts, the `draft` rows of `catalog_versions`. Callers own the transaction.
 *
 * A draft row is what a draft's content rows belong to (their `version_id`), and each holds its own
 * copy of its content. An authored catalog has one next version and any number of long-term drafts
 * (contract ADR 0002, amendment 2).
 */
@Component
class CatalogDrafts {

    /**
     * The draft a caller means: the one its message targets (see [CatalogVersionTargeting]), else the
     * catalog's next version. Null when there is none (a subscribed catalog, or a next version that was
     * discarded and not opened again).
     */
    fun draft(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): CatalogDraft? = handle
        .createQuery("$SELECT WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND kind = 'draft'")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map(::toDraft)
        .findOne()
        .orElse(null)

    /** The catalog's next version, whichever draft a message targets; null when it has none. */
    fun next(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): CatalogDraft? = drafts(handle, tenantKey, catalogKey).firstOrNull { it.isNext }

    /** Every open draft of the catalog: the next version first, then long-term work, oldest first. */
    fun drafts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDraft> = handle
        .createQuery("$SELECT WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND kind = 'draft' ORDER BY is_next DESC, created_at, version_id")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map(::toDraft)
        .list()

    /** The draft [ref] names, or null. */
    fun find(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, ref: DraftRef): CatalogDraft? = drafts(handle, tenantKey, catalogKey).firstOrNull { draft ->
        when (ref) {
            DraftRef.Next -> draft.isNext
            is DraftRef.Id -> draft.versionId == ref.id
        }
    }

    /**
     * Opens a draft on [base], empty; returns its `version_id`. Without a [name] it is the catalog's
     * next version, which must not have one yet; with one, it is long-term work.
     */
    fun open(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, base: String?, name: String? = null, createdFrom: String? = null): UUID = handle
        .createQuery(
            """
            INSERT INTO catalog_versions (tenant_key, catalog_id, kind, is_next, name, base_version, created_from)
            VALUES (:t, ${catalogAt("t", "c")}, 'draft', :next, :name, :base, :from)
            RETURNING version_id
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("next", name == null)
        .bind("name", name)
        .bind("base", base)
        .bind("from", createdFrom)
        .mapTo(UUID::class.java)
        .one()

    /**
     * After the next version [versionId] is released as [base], it continues as the next version: the
     * same row, so the content it holds, which the release copied, stays in place.
     */
    fun advance(handle: Handle, tenantKey: TenantKey, versionId: UUID, base: String) {
        val moved = handle.createUpdate(
            """
            UPDATE catalog_versions SET base_version = :base, created_from = NULL, created_at = NOW()
            WHERE tenant_key = :t AND version_id = :v AND kind = 'draft' AND is_next
            """,
        )
            .bind("t", tenantKey)
            .bind("v", versionId)
            .bind("base", base)
            .execute()
        check(moved == 1) { "no next version $versionId to advance" }
    }

    /**
     * Makes long-term work [promoted] the next version, on [base], in place of [previous]: that one is
     * deleted when [keepAs] is null, else kept as long-term work called [keepAs]. The old next version
     * stops being next before the new one starts, as the catalog has one next version at a time.
     */
    fun promote(handle: Handle, tenantKey: TenantKey, promoted: UUID, previous: UUID?, keepAs: String?, base: String?) {
        if (previous != null) {
            if (keepAs == null) {
                delete(handle, tenantKey, previous)
            } else {
                handle.createUpdate("UPDATE catalog_versions SET is_next = FALSE, name = :name WHERE tenant_key = :t AND version_id = :v AND is_next")
                    .bind("t", tenantKey)
                    .bind("v", previous)
                    .bind("name", keepAs)
                    .execute()
            }
        }
        val moved = handle.createUpdate(
            "UPDATE catalog_versions SET is_next = TRUE, name = NULL, base_version = :base WHERE tenant_key = :t AND version_id = :v AND kind = 'draft' AND NOT is_next",
        )
            .bind("t", tenantKey)
            .bind("v", promoted)
            .bind("base", base)
            .execute()
        check(moved == 1) { "no long-term work $promoted to promote" }
    }

    /** Renames long-term work [versionId]. */
    fun rename(handle: Handle, tenantKey: TenantKey, versionId: UUID, name: String) {
        handle.createUpdate("UPDATE catalog_versions SET name = :name WHERE tenant_key = :t AND version_id = :v AND kind = 'draft' AND NOT is_next")
            .bind("t", tenantKey)
            .bind("v", versionId)
            .bind("name", name)
            .execute()
    }

    /**
     * Discards draft [versionId] and every content row, ready mark and dependency declaration it
     * holds (they cascade from it). A resource's identity stays while another version holds it.
     */
    fun delete(handle: Handle, tenantKey: TenantKey, versionId: UUID) {
        handle.createUpdate("DELETE FROM catalog_versions WHERE tenant_key = :t AND version_id = :v AND kind = 'draft'")
            .bind("t", tenantKey)
            .bind("v", versionId)
            .execute()
    }

    /** Copies every content row of version [from] into version [to] of the same catalog, keeping identities. */
    fun copyContent(handle: Handle, tenantKey: TenantKey, from: UUID, to: UUID) {
        handle.createQuery("SELECT copy_catalog_version_content(:t, :from, :to)::text")
            .bind("t", tenantKey)
            .bind("from", from)
            .bind("to", to)
            .mapTo(String::class.java)
            .findOne()
    }

    private fun toDraft(rs: ResultSet, @Suppress("UNUSED_PARAMETER") ctx: StatementContext) = CatalogDraft(
        rs.getObject("version_id", UUID::class.java),
        rs.getBoolean("is_next"),
        rs.getString("name"),
        rs.getString("base_version"),
        rs.getString("created_from"),
        rs.getObject("created_at", OffsetDateTime::class.java),
    )

    companion object {
        private const val SELECT = "SELECT version_id, is_next, name, base_version, created_from, created_at FROM catalog_versions"

        /** The number a catalog's first release gets when nothing says otherwise. */
        const val FIRST: String = "1.0.0"

        /** The highest of [releases] below [version], by SemVer; null when none is. */
        fun highestBelow(version: String, releases: Collection<String>): String? {
            val own = SemVer.parseOrNull(version) ?: return null
            return releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }.filter { it.first < own }.maxByOrNull { it.first }?.second
        }

        /** The highest of [releases] by SemVer; null when there is none. */
        fun latest(releases: Collection<String>): String? = releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }.maxByOrNull { it.first }?.second

        /**
         * The released versions a draft on [base] is behind (contract ADR 0002): every release above its
         * base, lowest first. Without a base, every release.
         */
        fun behind(base: String?, releases: Collection<String>): List<String> {
            val floor = base?.let(SemVer::parseOrNull)
            return releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }
                .filter { (semVer, _) -> floor == null || semVer > floor }
                .sortedBy { it.first }
                .map { it.second }
        }
    }
}
