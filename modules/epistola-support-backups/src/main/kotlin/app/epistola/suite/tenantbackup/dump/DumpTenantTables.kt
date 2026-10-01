// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup.dump

import app.epistola.suite.tenantbackup.schema.TableSpec
import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import app.epistola.suite.tenantbackup.schema.TenantTopology
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** One backed-up table: its spec plus the tenant's rows as type-stable JSON-native maps. */
data class DumpedTable(
    val spec: TableSpec,
    val rows: List<Map<String, Any?>>,
)

/**
 * One backed-up asset blob from the content-addressable `asset_content` store (#738),
 * identified by its `(scope, contentHash)` — the same coordinates the tenant's
 * `assets.content_hash` rows point at.
 */
class DumpedBlob(
    val scope: String,
    val contentHash: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: String,
    val content: ByteArray,
)

/** A full tenant dump: every INCLUDE table in FK order plus the tenant's asset blobs. */
data class TenantDump(
    val tables: List<DumpedTable>,
    val blobs: List<DumpedBlob>,
)

/**
 * Reads all of a tenant's INCLUDE-table rows and asset blobs into a type-stable in-memory form,
 * using raw column reads (no domain/Secret column mappers) so credential `enc:v1:` envelopes pass
 * through verbatim. Mechanism only — invoked by `BuildTenantBackup` inside its own handle.
 */
@Component
class DumpTenantTables(
    private val codec: TableRowCodec,
) {
    fun dump(
        handle: Handle,
        topology: TenantTopology,
        tenantKey: String,
    ): TenantDump {
        val tables =
            topology.orderedTables.map { spec ->
                val whereColumn = if (spec.table == TenantTableTopology.TENANTS) "id" else "tenant_key"
                val rows =
                    handle
                        .createQuery(codec.buildSelect(spec, whereColumn))
                        .bind("tk", tenantKey)
                        .mapToMap()
                        .list()
                        .map { row -> row.entries.associate { (k, v) -> k.lowercase() to v } }
                DumpedTable(spec, rows)
            }
        return TenantDump(tables, dumpBlobs(handle, tenantKey))
    }

    /**
     * The asset_content blobs this tenant holds.
     *
     * Two holders, the same two the content reaper refuses to collect. Its **assets**, resolved
     * through the `content_hash` pointer and the derived dedup scope
     * (`sensitive ? tenant_key : 'global'`). And its **retained revisions**, which name the bytes a
     * released resource needs directly — a release whose image has since been deleted from the
     * working copy has no asset row left to reach them through, and restoring without them would
     * restore a release that can no longer be reproduced.
     *
     * One pass over `asset_content` asking whether either holder exists, and DISTINCT, because many
     * assets may share one deduplicated blob (#738) and a revision may name one an asset still
     * points at.
     */
    private fun dumpBlobs(
        handle: Handle,
        tenantKey: String,
    ): List<DumpedBlob> = handle
        .createQuery(
            "SELECT DISTINCT ac.scope, ac.content_hash, ac.content_type, ac.size_bytes, " +
                "  ac.created_at::text AS created_at, ac.content " +
                "FROM asset_content ac " +
                "WHERE EXISTS (" +
                "    SELECT 1 FROM assets a WHERE a.tenant_key = :tk AND a.content_hash = ac.content_hash " +
                "      AND ac.scope = CASE WHEN a.sensitive THEN a.tenant_key::text ELSE 'global' END" +
                "  ) OR EXISTS (" +
                "    SELECT 1 FROM revision_binaries rb WHERE rb.tenant_key = :tk " +
                "      AND rb.content_hash = ac.content_hash AND rb.scope = ac.scope" +
                "  ) " +
                "ORDER BY ac.scope, ac.content_hash",
        ).bind("tk", tenantKey)
        .map { rs, _ ->
            DumpedBlob(
                scope = rs.getString("scope"),
                contentHash = rs.getString("content_hash"),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                createdAt = rs.getString("created_at"),
                content = rs.getBytes("content"),
            )
        }.list()
}
