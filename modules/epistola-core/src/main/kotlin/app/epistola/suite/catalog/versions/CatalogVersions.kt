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
 * One version of a catalog: an open draft, or a release.
 *
 * @property base the released version a draft started from; null for a release and for a draft of a
 *   catalog that was never released.
 * @property createdFrom the version a draft was created from, when it was not its base.
 * @property behind for a draft, the released versions above its base and below its number, lowest
 *   first; it does not contain their changes unless an author applied them by hand.
 * @property deployedTo the environments that deploy a released version.
 */
data class CatalogVersion(
    val version: String,
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
)

/** A released version a draft is behind, and what that release changed against the release below it. */
data class BehindRelease(val version: String, val changes: List<ReleasedChange>)

/** A resource a release added, modified or removed. [change] is `added`, `modified` or `removed`. */
data class ReleasedChange(val type: String, val key: String, val change: String)

/** An open draft of an authored catalog. [versionId] is what its content rows belong to. */
data class CatalogDraft(
    val versionId: UUID,
    val version: String,
    val base: String?,
    val createdFrom: String?,
    val createdAt: OffsetDateTime,
)

/**
 * The SQL of catalog drafts, the `draft` rows of `catalog_versions`. Callers own the transaction.
 *
 * A draft row is what a draft's content rows belong to (their `version_id`). A catalog may have any
 * number of drafts (contract ADR 0002); each holds its own copy of its content.
 */
@Component
class CatalogDrafts {

    /**
     * The draft a caller means: the one its message targets (see [CatalogVersionTargeting]), else the
     * catalog's highest draft. Null when the catalog has none (a subscribed one, or every draft
     * discarded).
     */
    fun draft(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): CatalogDraft? = handle
        .createQuery("$SELECT WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")} AND kind = 'draft'")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map(::toDraft)
        .findOne()
        .orElse(null)

    /** Every open draft of the catalog, in no particular order. */
    fun drafts(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDraft> = handle
        .createQuery("$SELECT WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND kind = 'draft'")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map(::toDraft)
        .list()

    /** The draft numbered [version], or null. */
    fun named(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): CatalogDraft? = drafts(handle, tenantKey, catalogKey).firstOrNull { it.version == version }

    /** Opens [version] as a draft on [base], empty; returns its `version_id`. */
    fun open(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, base: String?, createdFrom: String? = null): UUID = handle
        .createQuery(
            """
            INSERT INTO catalog_versions (tenant_key, catalog_id, version, kind, base_version, created_from)
            VALUES (:t, ${catalogAt("t", "c")}, :version, 'draft', :base, :from)
            RETURNING version_id
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("version", version)
        .bind("base", base)
        .bind("from", createdFrom)
        .mapTo(UUID::class.java)
        .one()

    /**
     * After draft [versionId] is released as [base], it carries on as [next]: the same row, so the
     * content it holds, which the release copied, stays in place as the next draft's.
     */
    fun advance(handle: Handle, tenantKey: TenantKey, versionId: UUID, next: String, base: String) {
        val moved = handle.createUpdate(
            """
            UPDATE catalog_versions SET version = :next, base_version = :base, created_from = NULL, created_at = NOW()
            WHERE tenant_key = :t AND version_id = :v AND kind = 'draft'
            """,
        )
            .bind("t", tenantKey)
            .bind("v", versionId)
            .bind("next", next)
            .bind("base", base)
            .execute()
        check(moved == 1) { "no draft $versionId to advance" }
    }

    /** Gives draft [from] another number; its content and base stay. */
    fun renumber(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, from: String, to: String) {
        handle.createUpdate("UPDATE catalog_versions SET version = :to WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND kind = 'draft' AND version = :from")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("from", from)
            .bind("to", to)
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
        rs.getString("version"),
        rs.getString("base_version"),
        rs.getString("created_from"),
        rs.getObject("created_at", OffsetDateTime::class.java),
    )

    companion object {
        private const val SELECT = "SELECT version_id, version, base_version, created_from, created_at FROM catalog_versions"

        /** The draft a release opens: its next patch. */
        fun nextAfter(released: String): String = SemVer.parseOrNull(released)?.bumpPatch()?.toString() ?: "1.0.0"

        /** The number a catalog's first draft gets. */
        const val FIRST: String = "1.0.0"

        /** The highest of [releases] below [version], by SemVer; null when none is. */
        fun highestBelow(version: String, releases: Collection<String>): String? {
            val own = SemVer.parseOrNull(version) ?: return null
            return releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }.filter { it.first < own }.maxByOrNull { it.first }?.second
        }

        /**
         * The released versions a draft numbered [version] on [base] is behind (contract ADR 0002): those
         * above its base and below its number, lowest first. Without a base, every release below it.
         */
        fun behind(version: String, base: String?, releases: Collection<String>): List<String> {
            val own = SemVer.parseOrNull(version) ?: return emptyList()
            val floor = base?.let(SemVer::parseOrNull)
            return releases.mapNotNull { r -> SemVer.parseOrNull(r)?.let { it to r } }
                .filter { (semVer, _) -> semVer < own && (floor == null || semVer > floor) }
                .sortedBy { it.first }
                .map { it.second }
        }
    }
}
