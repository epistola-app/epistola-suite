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
 * One version of a catalog: an open draft, or a release (contract ADR 0002).
 *
 * Every version has a [versionId] (contract `CatalogVersionId`), which a draft keeps when it is
 * released. A draft has no number until then: [version] is null for one. Which draft is the next
 * version ([isNext]) is a convention of this suite, not of the contract: the one everyday work goes
 * into. Any other is named ([name]).
 *
 * @property base the release a draft started from, fixed; for a release, the one its draft started
 *   from. Null for a draft started when the catalog had no release, a catalog's first release, a
 *   release installed from elsewhere, and a release cut before releases recorded it.
 * @property createdFrom for a draft, the [versionId] of the draft it was copied from, unreleased
 *   changes included; null when it was started from a release or from nothing.
 * @property behind for a draft, the releases it is behind for the number it would be released as
 *   ([CatalogDrafts.behind]), lowest first; filled only by queries that ask for it.
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
    val deployedTo: List<String> = emptyList(),
    val behind: List<BehindRelease> = emptyList(),
    val versionId: String,
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
        else -> DraftRef.Id(versionId)
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
 * An open draft of an authored catalog. [versionId] is what its content rows belong to; [key] is its
 * public id (contract `CatalogVersionId`), which its release keeps. The [isNext] one is the catalog's
 * next version, a convention of this suite; any other has a [name].
 */
data class CatalogDraft(
    val versionId: UUID,
    val key: String,
    val isNext: Boolean,
    val name: String?,
    val base: String?,
    val createdFrom: String?,
    val createdAt: OffsetDateTime,
) {
    val label: String get() = if (isNext) NEXT_LABEL else name ?: NEXT_LABEL

    val ref: DraftRef get() = if (isNext) DraftRef.Next else DraftRef.Id(key)

    companion object {
        /** What the next version is always called: it has no name of its own. */
        const val NEXT_LABEL: String = "Next version"
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
            is DraftRef.Id -> draft.key == ref.key
        }
    }

    /**
     * Opens a draft on [base], empty; returns its `version_id`. Without a [name] it is the catalog's
     * next version, which must not have one yet; with one, it is another draft. [createdFrom] is the
     * key of the draft it is a copy of, if any.
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
     * After the next version [versionId] is released as [base], a new next version continues on the
     * release: the same row, so the content it holds, which the release copied, stays in place, under
     * a new key, since its old one now addresses the release.
     */
    fun advance(handle: Handle, tenantKey: TenantKey, versionId: UUID, base: String) {
        val moved = handle.createUpdate(
            """
            UPDATE catalog_versions SET base_version = :base, created_from = NULL, created_at = NOW(), version_key = epistola_version_key()
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
     * Makes draft [promoted] the next version in place of [previous]: that one is deleted when
     * [keepAs] is null, else kept as another draft called [keepAs]. Neither base moves: a base is
     * fixed (contract ADR 0002). The old next version stops being next before the new one starts, as
     * the catalog has one next version at a time.
     */
    fun promote(handle: Handle, tenantKey: TenantKey, promoted: UUID, previous: UUID?, keepAs: String?) {
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
            "UPDATE catalog_versions SET is_next = TRUE, name = NULL WHERE tenant_key = :t AND version_id = :v AND kind = 'draft' AND NOT is_next",
        )
            .bind("t", tenantKey)
            .bind("v", promoted)
            .execute()
        check(moved == 1) { "no draft $promoted to make the next version" }
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
        rs.getString("version_key"),
        rs.getBoolean("is_next"),
        rs.getString("name"),
        rs.getString("base_version"),
        rs.getString("created_from"),
        rs.getObject("created_at", OffsetDateTime::class.java),
    )

    companion object {
        private const val SELECT = "SELECT version_id, version_key, is_next, name, base_version, created_from, created_at FROM catalog_versions"

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
         * The releases a draft on [base], started at [createdAt], is behind when it is released as
         * [number] (contract ADR 0002, "A draft can be behind"), lowest first: every release
         * (a) numbered above its base and below [number], or (b) published after the draft was
         * started and numbered below [number], other than its base. [releases] are the catalog's
         * releases with when each was published.
         */
        fun behind(base: String?, createdAt: OffsetDateTime?, number: String, releases: Collection<Pair<String, OffsetDateTime?>>): List<String> {
            val ceiling = SemVer.parseOrNull(number) ?: return emptyList()
            val floor = base?.let(SemVer::parseOrNull)
            return releases.mapNotNull { (r, releasedAt) -> SemVer.parseOrNull(r)?.let { Triple(it, r, releasedAt) } }
                .filter { (semVer, r, releasedAt) ->
                    val below = semVer < ceiling
                    val aboveBase = floor == null || semVer > floor
                    val publishedSince = createdAt != null && releasedAt != null && releasedAt.isAfter(createdAt)
                    r != base && below && (aboveBase || publishedSince)
                }
                .sortedBy { it.first }
                .map { it.second }
        }
    }
}
