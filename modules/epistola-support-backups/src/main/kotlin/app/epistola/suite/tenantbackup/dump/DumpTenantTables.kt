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

/** One backed-up asset blob from `content_store` (bytes base64-encoded). */
data class DumpedBlob(
    val key: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: String,
    val base64: String,
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

    private fun dumpBlobs(
        handle: Handle,
        tenantKey: String,
    ): List<DumpedBlob> {
        val prefix = TenantTableTopology.assetBlobPrefix(tenantKey)
        return handle
            .createQuery(
                "SELECT key, content_type, size_bytes, created_at::text AS created_at, " +
                    "encode(content, 'base64') AS content_b64 " +
                    "FROM content_store WHERE left(key, :len) = :prefix ORDER BY key",
            ).bind("len", prefix.length)
            .bind("prefix", prefix)
            .map { rs, _ ->
                DumpedBlob(
                    key = rs.getString("key"),
                    contentType = rs.getString("content_type"),
                    sizeBytes = rs.getLong("size_bytes"),
                    createdAt = rs.getString("created_at"),
                    base64 = rs.getString("content_b64"),
                )
            }.list()
    }
}
