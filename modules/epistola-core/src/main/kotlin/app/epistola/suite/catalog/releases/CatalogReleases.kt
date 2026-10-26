// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Whether a catalog version can still be edited (contract ADR 0002). */
enum class CatalogReleaseStatus { DRAFT, RELEASED }

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
 *   ([ReleaseLineage.behind]), lowest first; filled only by queries that ask for it.
 * @property deployedTo the environments that deploy a released version.
 */
data class CatalogRelease(
    val version: String?,
    val status: CatalogReleaseStatus,
    val base: String? = null,
    val createdFrom: String? = null,
    val createdAt: OffsetDateTime? = null,
    val releasedAt: OffsetDateTime? = null,
    val notes: String? = null,
    val fingerprint: String? = null,
    /**
     * A release's identity (ADR 0034): the V6 fingerprint of its content. Null for a draft, and for a
     * release recorded before content hashes until it is computed.
     */
    val contentHash: String? = null,
    val deployedTo: List<String> = emptyList(),
    val behind: List<BehindRelease> = emptyList(),
    /**
     * For a draft that is [behind] releases, the line it continues and where each of them was made
     * off it ([ReleaseLineage.behindLine]); null otherwise. Filled with [behind].
     */
    val behindLine: BehindLine? = null,
    val versionId: String,
    val name: String? = null,
    val isNext: Boolean = false,
    /**
     * Raised when a draft's name, default theme, presets or dependency declarations change (REST
     * `ETag` / `If-Match`); a release does not change, so it stays at 1.
     */
    val revision: Long = 1,
) {
    /** What people call it: "Next release", parallel work's name, or a release's number. */
    val label: String get() = when {
        status == CatalogReleaseStatus.RELEASED -> "v$version"
        isNext -> CatalogDraft.NEXT_LABEL
        else -> name ?: CatalogDraft.NEXT_LABEL
    }

    /** How a message names this draft; null for a release. */
    val draftRef: DraftRef? get() = when {
        status == CatalogReleaseStatus.RELEASED -> null
        isNext -> DraftRef.Next
        else -> DraftRef.Id(versionId)
    }
}

/**
 * A released version a draft is behind, and what that release changed against its own base (the
 * release below it when it recorded none).
 *
 * @property base the release it was made on, as its line names it here ([ReleaseLineage]): a number
 *   this catalog may not have, for a release received from elsewhere; null when it records none.
 * @property releasedAt when it was published here.
 */
data class BehindRelease(
    val version: String,
    val changes: List<ReleasedChange>,
    val base: String? = null,
    val releasedAt: OffsetDateTime? = null,
)

/**
 * A resource a release added, modified or removed. [change] is `added`, `modified` or `removed`;
 * [name] is what the release calls it (for a removed one, what its base did), null when neither says.
 */
data class ReleasedChange(val type: String, val key: String, val change: String, val name: String? = null)

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
    /** Raised when its name, default theme, presets or dependency declarations change (REST `ETag`). */
    val revision: Long = 1,
    /** The releases merged into it (ADR 0032): with its base, the roots of its line ([ReleaseLineage.containedBy]). */
    val merged: Set<String> = emptySet(),
) {
    val label: String get() = if (isNext) NEXT_LABEL else name ?: NEXT_LABEL

    val ref: DraftRef get() = if (isNext) DraftRef.Next else DraftRef.Id(key)

    companion object {
        /** What the next release is always called: it has no name of its own. */
        const val NEXT_LABEL: String = "Next release"

        /** What the next release was called before releases were called releases; still no draft's name. */
        const val FORMER_NEXT_LABEL: String = "Next version"
    }
}

/**
 * The SQL of catalog drafts, the `draft` rows of `catalog_versions`. Callers own the transaction.
 *
 * A draft row is what a draft's content rows belong to (their `version_id`), and each holds its own
 * copy of its content. An authored catalog has one next version and any number of parallel drafts
 * (contract ADR 0002, amendment 2).
 */
@Component
class CatalogDrafts {

    /**
     * The draft a caller means: the one its message targets (see [CatalogReleaseTargeting]), else the
     * catalog's next version. Null when there is none (a subscribed catalog, or a next version that was
     * discarded and not opened again).
     */
    fun draft(handle: Handle, catalog: CatalogId): CatalogDraft? = handle
        .createQuery("$SELECT WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND state = 'draft'")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .map(::toDraft)
        .findOne()
        .orElse(null)

    /** The catalog's next version, whichever draft a message targets; null when it has none. */
    fun next(handle: Handle, catalog: CatalogId): CatalogDraft? = drafts(handle, catalog).firstOrNull { it.isNext }

    /** Every open draft of the catalog: the next version first, then parallel work, oldest first. */
    fun drafts(handle: Handle, catalog: CatalogId): List<CatalogDraft> = handle
        .createQuery("$SELECT WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND state = 'draft' ORDER BY is_next DESC, created_at, version_id")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .map(::toDraft)
        .list()

    /** The draft [ref] names, or null. */
    fun find(handle: Handle, catalog: CatalogId, ref: DraftRef): CatalogDraft? = drafts(handle, catalog).firstOrNull { draft ->
        when (ref) {
            DraftRef.Next -> draft.isNext
            is DraftRef.Id -> draft.key == ref.key
            is DraftRef.Released -> false
        }
    }

    /**
     * Opens a draft on [base], empty; returns its `version_id`. Without a [name] it is the catalog's
     * next version, which must not have one yet; with one, it is another draft. [createdFrom] is the
     * key of the draft it is a copy of, if any.
     */
    fun open(handle: Handle, catalog: CatalogId, base: String?, name: String? = null, createdFrom: String? = null): UUID = handle
        .createQuery(
            """
            INSERT INTO catalog_versions (tenant_key, catalog_id, state, is_next, name, base_version, created_from)
            VALUES (:t, ${catalogAt("t", "c")}, 'draft', :next, :name, :base, :from)
            RETURNING version_id
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("next", name == null)
        .bind("name", name)
        .bind("base", base)
        .bind("from", createdFrom)
        .mapTo(UUID::class.java)
        .one()

    /**
     * Draft [versionId] becomes release [version] (ADR 0036): its row is frozen in place, keeping its
     * id, key and name, with the record [version]'s `catalog_releases` row holds. It stops being the
     * next version; [startNext] opens the one that continues on it.
     */
    fun freeze(handle: Handle, tenantKey: TenantKey, versionId: UUID, version: String) {
        val frozen = handle.createUpdate(
            """
            UPDATE catalog_versions v SET state = 'released', is_next = FALSE, version = r.version,
                fingerprint = r.fingerprint, content_hash = r.content_hash, notes = r.notes, manifest_snapshot = r.manifest_snapshot,
                rendering_defaults_version = r.rendering_defaults_version, released_at = r.released_at, released_by = r.released_by,
                released_by_installation = r.released_by_installation, base_version = r.base_version, base_fingerprint = r.base_fingerprint,
                base_version_id = (SELECT b.version_id FROM catalog_versions b
                                   WHERE b.tenant_key = r.tenant_key AND b.catalog_id = r.catalog_id AND b.version = r.base_version AND b.state = 'released')
            FROM catalog_releases r
            WHERE v.tenant_key = :t AND v.version_id = :v AND v.state = 'draft'
              AND r.tenant_key = v.tenant_key AND r.catalog_id = v.catalog_id AND r.version = :version AND r.version_key = v.version_key
            """,
        )
            .bind("t", tenantKey)
            .bind("v", versionId)
            .bind("version", version)
            .execute()
        check(frozen == 1) { "no draft $versionId to release as $version" }
    }

    /**
     * After the next version is released as [released] ([freeze]), opens the next version that
     * continues on it: a new row with a new key, holding a copy of what the release holds (its content
     * rows, ready marks, code list refreshes, dependency declarations and theme settings), measured
     * against the release. Returns the new next version's `version_id`.
     */
    fun startNext(handle: Handle, catalog: CatalogId, released: UUID, base: String): UUID {
        val next = handle.createQuery(
            """
            INSERT INTO catalog_versions (tenant_key, catalog_id, state, is_next, base_version, base_version_id, default_theme_resource_id, presets)
            SELECT tenant_key, catalog_id, 'draft', TRUE, :base, version_id, default_theme_resource_id, presets
            FROM catalog_versions WHERE tenant_key = :t AND version_id = :from AND state = 'released'
            RETURNING version_id
            """,
        )
            .bind("t", catalog.tenantKey)
            .bind("from", released)
            .bind("base", base)
            .mapTo(UUID::class.java)
            .one()
        handle.createQuery("SELECT copy_catalog_version_content(:t, :from, :to)::text")
            .bind("t", catalog.tenantKey)
            .bind("from", released)
            .bind("to", next)
            .mapTo(String::class.java)
            .findOne()
        return next
    }

    /**
     * Released version [versionId] keeps no draft bookkeeping: its ready marks, code list refreshes and
     * dependency declarations go, since what it contains, and the releases it pins, are recorded with
     * the release. Its content rows stay: they are the release's (ADR 0036).
     */
    fun retire(handle: Handle, tenantKey: TenantKey, versionId: UUID) {
        for (table in listOf("resource_ready_marks", "code_list_refreshes", "catalog_dependencies")) {
            handle.createUpdate("DELETE FROM $table WHERE tenant_key = :t AND version_id = :v")
                .bind("t", tenantKey)
                .bind("v", versionId)
                .execute()
        }
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
            "UPDATE catalog_versions SET is_next = TRUE, name = NULL WHERE tenant_key = :t AND version_id = :v AND state = 'draft' AND NOT is_next",
        )
            .bind("t", tenantKey)
            .bind("v", promoted)
            .execute()
        check(moved == 1) { "no draft $promoted to make the next version" }
    }

    /**
     * Before an update of draft [versionId] that names the revision it read: locks the row for the rest
     * of the transaction and refuses a stale [expected] ([Revisions.refuse]). Null expects nothing.
     */
    fun requireRevision(handle: Handle, tenantKey: TenantKey, versionId: UUID, expected: Long?, resource: String) {
        if (expected == null) return
        val current = handle.createQuery("SELECT revision FROM catalog_versions WHERE tenant_key = :t AND version_id = :v FOR UPDATE")
            .bind("t", tenantKey)
            .bind("v", versionId)
            .mapTo(Long::class.java)
            .findOne()
            .orElse(null)
        Revisions.refuse(resource, expected, current)
    }

    /** Renames parallel work [versionId]. */
    fun rename(handle: Handle, tenantKey: TenantKey, versionId: UUID, name: String) {
        handle.createUpdate("UPDATE catalog_versions SET name = :name WHERE tenant_key = :t AND version_id = :v AND state = 'draft' AND NOT is_next")
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
        handle.createUpdate("DELETE FROM catalog_versions WHERE tenant_key = :t AND version_id = :v AND state = 'draft'")
            .bind("t", tenantKey)
            .bind("v", versionId)
            .execute()
    }

    /** The fingerprints the next version had when a received release was put in place in it; null when not recorded. */
    fun baseFingerprints(handle: Handle, tenantKey: TenantKey, versionId: UUID): String? = handle
        .createQuery("SELECT base_fingerprints::text FROM catalog_versions WHERE tenant_key = :t AND version_id = :v")
        .bind("t", tenantKey)
        .bind("v", versionId)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

    /** Records what draft [versionId] holds now as what a received release put in place ([baseFingerprints]). */
    fun recordBaseFingerprints(handle: Handle, tenantKey: TenantKey, versionId: UUID, fingerprintsJson: String) {
        handle.createUpdate("UPDATE catalog_versions SET base_fingerprints = CAST(:f AS JSONB) WHERE tenant_key = :t AND version_id = :v")
            .bind("t", tenantKey)
            .bind("v", versionId)
            .bind("f", fingerprintsJson)
            .execute()
    }

    /**
     * Records that draft [versionId] has merged [release] (ADR 0032), so it is no longer behind it:
     * against [mergeBase], with the [resolutionsJson] (`{"type/key": "OURS"|"THEIRS"}`) chosen for its
     * conflicts. A release merged before stays as it was recorded.
     */
    fun recordMerged(
        handle: Handle,
        tenantKey: TenantKey,
        versionId: UUID,
        release: String,
        mergeBase: String? = null,
        resolutionsJson: String = "{}",
        heldFingerprintsJson: String? = null,
    ) {
        handle.createUpdate(
            """
            INSERT INTO catalog_draft_merges (tenant_key, version_id, release_version, merge_base, resolutions, held_fingerprints, merged_by)
            SELECT :t, version_id, :release, :base, CAST(:resolutions AS JSONB), CAST(:held AS JSONB), :by
            FROM catalog_versions WHERE tenant_key = :t AND version_id = :v AND state = 'draft'
            ON CONFLICT (tenant_key, version_id, release_version) DO NOTHING
            """,
        )
            .bind("t", tenantKey)
            .bind("v", versionId)
            .bind("release", release)
            .bind("base", mergeBase)
            .bind("resolutions", resolutionsJson)
            .bind("held", heldFingerprintsJson)
            .bind("by", app.epistola.suite.security.currentUserIdOrNull()?.value)
            .execute()
    }

    /**
     * What draft [versionId] held of [release] once it merged it (`catalog_draft_merges.held_fingerprints`):
     * a fingerprint per entry; null when not recorded.
     */
    fun mergeHeldFingerprints(handle: Handle, tenantKey: TenantKey, versionId: UUID, release: String): String? = handle
        .createQuery("SELECT held_fingerprints::text FROM catalog_draft_merges WHERE tenant_key = :t AND version_id = :v AND release_version = :r")
        .bind("t", tenantKey)
        .bind("v", versionId)
        .bind("r", release)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)

    /** Copies every content row of version [from] into version [to] of the same catalog, keeping identities. */
    fun copyContent(handle: Handle, tenantKey: TenantKey, from: UUID, to: UUID) {
        handle.createQuery("SELECT copy_catalog_version_content(:t, :from, :to)::text")
            .bind("t", tenantKey)
            .bind("from", from)
            .bind("to", to)
            .mapTo(String::class.java)
            .findOne()
        // The version's theme settings (contract ADR 0007) are part of what it holds, and so are the
        // releases merged into it (ADR 0032).
        handle.createUpdate(
            """
            UPDATE catalog_versions t SET default_theme_resource_id = f.default_theme_resource_id, presets = f.presets,
                base_fingerprints = f.base_fingerprints
            FROM catalog_versions f
            WHERE t.tenant_key = :t AND t.version_id = :to AND f.tenant_key = :t AND f.version_id = :from
            """,
        )
            .bind("t", tenantKey)
            .bind("from", from)
            .bind("to", to)
            .execute()
        handle.createUpdate(
            """
            INSERT INTO catalog_draft_merges (tenant_key, version_id, release_version, merge_base, resolutions, held_fingerprints, merged_by, merged_at)
            SELECT tenant_key, :to, release_version, merge_base, resolutions, held_fingerprints, merged_by, merged_at
            FROM catalog_draft_merges WHERE tenant_key = :t AND version_id = :from
            ON CONFLICT (tenant_key, version_id, release_version) DO NOTHING
            """,
        )
            .bind("t", tenantKey)
            .bind("from", from)
            .bind("to", to)
            .execute()
    }

    private fun toDraft(rs: ResultSet, @Suppress("UNUSED_PARAMETER") ctx: StatementContext) = CatalogDraft(
        rs.getObject("version_id", UUID::class.java),
        rs.getString("version_key"),
        rs.getBoolean("is_next"),
        rs.getString("name"),
        rs.getString("base_version"),
        rs.getString("created_from"),
        rs.getObject("created_at", OffsetDateTime::class.java),
        rs.getLong("revision"),
        (rs.getArray("merged_releases").array as Array<*>).map { it as String }.toSet(),
    )

    companion object {
        private const val SELECT = "SELECT version_id, version_key, is_next, name, base_version, created_from, created_at, revision, " +
            "ARRAY(SELECT m.release_version FROM catalog_draft_merges m WHERE m.tenant_key = catalog_versions.tenant_key AND m.version_id = catalog_versions.version_id) AS merged_releases " +
            "FROM catalog_versions"

        /** The number a catalog's first release gets when nothing says otherwise. */
        const val FIRST: String = "1.0.0"

        /** The highest of [releases] below [version], by SemVer; null when none is. */
        fun highestBelow(version: String, releases: Collection<String>): String? {
            val own = SemVer.parseOrNull(version) ?: return null
            return releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }.filter { it.first < own }.maxByOrNull { it.first }?.second
        }

        /** The highest of [releases] by SemVer; null when there is none. */
        fun latest(releases: Collection<String>): String? = releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }.maxByOrNull { it.first }?.second
    }
}
