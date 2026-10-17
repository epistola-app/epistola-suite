// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/** Whether a catalog version can still be edited (contract ADR 0002). */
enum class CatalogVersionStatus { DRAFT, RELEASED }

/**
 * One version of a catalog: its open draft, or a release.
 *
 * @property base the released version a draft started from; null for a release and for a draft of a
 *   catalog that was never released.
 * @property createdFrom the version a draft was created from, when it was not its base.
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
)

/** The open draft of an authored catalog. */
data class CatalogDraft(
    val version: String,
    val base: String?,
    val createdFrom: String?,
    val createdAt: OffsetDateTime,
)

/** The SQL of catalog drafts. Callers own the transaction. */
@Component
class CatalogDrafts {

    fun draft(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): CatalogDraft? = handle
        .createQuery("SELECT version, base_version, created_from, created_at FROM catalog_drafts WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> CatalogDraft(rs.getString("version"), rs.getString("base_version"), rs.getString("created_from"), rs.getObject("created_at", OffsetDateTime::class.java)) }
        .findOne()
        .orElse(null)

    /** Opens [version] as the catalog's draft on [base], replacing any draft it had. */
    fun open(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, base: String?, createdFrom: String? = null) {
        handle.createUpdate("DELETE FROM catalog_drafts WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .execute()
        handle.createUpdate(
            """
            INSERT INTO catalog_drafts (tenant_key, catalog_id, version, base_version, created_from)
            VALUES (:t, ${catalogAt("t", "c")}, :version, :base, :from)
            """,
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("version", version)
            .bind("base", base)
            .bind("from", createdFrom)
            .execute()
    }

    /** Gives the draft another number; its content and base stay. */
    fun renumber(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, to: String) {
        handle.createUpdate("UPDATE catalog_drafts SET version = :to WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .bind("to", to)
            .execute()
    }

    companion object {
        /** The draft a release opens: its next patch. */
        fun nextAfter(released: String): String = SemVer.parseOrNull(released)?.bumpPatch()?.toString() ?: "1.0.0"

        /** The number a catalog's first draft gets. */
        const val FIRST: String = "1.0.0"
    }
}
