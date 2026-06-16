package app.epistola.suite.tenantbackup.restore

import app.epistola.suite.tenantbackup.BackupTableEntry
import app.epistola.suite.tenantbackup.TenantBackupManifest
import app.epistola.suite.tenantbackup.dump.TableRowCodec
import app.epistola.suite.tenantbackup.schema.ColumnMeta
import app.epistola.suite.tenantbackup.schema.TableSpec
import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** Counts of what a merge-restore touched. */
data class MergeRestoreResult(
    val tablesRestored: Int,
    val rowsRestored: Int,
    val blobsRestored: Int,
)

/**
 * Restores a tenant's tables and asset blobs by **merge**, never blanket delete — the mechanism
 * that keeps generated-document history alive across a restore. For every INCLUDE table:
 *
 *  1. **Upsert** every backup row (`INSERT … ON CONFLICT (pk) DO UPDATE`). Existing primary keys are
 *     updated, never deleted, so the `documents`/`generation` rows that FK-cascade off an unchanged
 *     `template_versions` row are untouched — the cascade-safety guarantee.
 *  2. **Delete only the live rows absent from the backup** (post-backup work-in-progress). Their
 *     cascade onto documents *is* the intended removal of work that never shipped.
 *
 * Upserts run parent→child (FK order, the manifest's order); deletes run child→parent (reverse) so
 * RESTRICT/NO-ACTION edges hold. The `tenants` row is updated in place and never deleted; its
 * default-theme pointer is nulled during the merge (breaking the `tenants`↔`themes` cycle) and
 * re-applied at the end. Runs inside the caller's single transaction/handle.
 */
@Component
class MergeRestoreTables(
    private val codec: TableRowCodec,
) {
    fun merge(
        handle: Handle,
        manifest: TenantBackupManifest,
        rowsByTable: Map<String, List<Map<String, Any?>>>,
        blobBytes: Map<String, ByteArray>,
        tenantKey: String,
    ): MergeRestoreResult {
        // Relax deferrable FKs (e.g. font_variants→assets) until commit.
        handle.execute("SET CONSTRAINTS ALL DEFERRED")

        val tenantEntry = manifest.tables.first { it.table == TenantTableTopology.TENANTS }
        val tenantRow = rowsByTable.getValue(TenantTableTopology.TENANTS).single()
        val targetThemeKey = tenantRow["default_theme_key"]
        val targetThemeCatalog = tenantRow["default_theme_catalog_key"]

        // Break the tenants↔themes cycle before any theme is removed.
        handle
            .createUpdate("UPDATE tenants SET default_theme_key = NULL WHERE id = :tk")
            .bind("tk", tenantKey)
            .execute()

        // Upsert phase — parent → child.
        var rowsRestored = 0
        manifest.tables.forEach { entry ->
            val spec = entry.toSpec()
            val rows = rowsByTable[entry.table].orEmpty()
            if (entry.table == TenantTableTopology.TENANTS) {
                val masked =
                    tenantRow + mapOf("default_theme_key" to null, "default_theme_catalog_key" to null)
                upsert(handle, spec, masked)
                rowsRestored += 1
            } else {
                rows.forEach { upsert(handle, spec, it) }
                rowsRestored += rows.size
            }
        }

        // Delete phase — child → parent, skipping the never-deleted tenants row.
        manifest.tables.reversed().forEach { entry ->
            if (entry.table != TenantTableTopology.TENANTS) {
                deleteAbsent(handle, entry.toSpec(), rowsByTable[entry.table].orEmpty(), tenantKey)
            }
        }

        val blobsRestored = mergeBlobs(handle, manifest, blobBytes, tenantKey)

        reapplyDefaultTheme(handle, tenantKey, targetThemeCatalog, targetThemeKey)

        return MergeRestoreResult(
            tablesRestored = manifest.tables.size,
            rowsRestored = rowsRestored,
            blobsRestored = blobsRestored,
        )
    }

    private fun upsert(
        handle: Handle,
        spec: TableSpec,
        row: Map<String, Any?>,
    ) {
        val update = handle.createUpdate(codec.buildUpsert(spec))
        codec.bindRow(update, spec, row).execute()
    }

    private fun deleteAbsent(
        handle: Handle,
        spec: TableSpec,
        backupRows: List<Map<String, Any?>>,
        tenantKey: String,
    ) {
        val livePks =
            handle
                .createQuery(codec.buildSelectPrimaryKeys(spec, "tenant_key"))
                .bind("tk", tenantKey)
                .mapToMap()
                .list()
                .map { row -> row.entries.associate { (k, v) -> k.lowercase() to v?.toString() } }

        val backupPkTuples =
            backupRows.map { row -> spec.primaryKey.map { row[it]?.toString() } }.toHashSet()

        livePks
            .filter { live -> spec.primaryKey.map { live[it] } !in backupPkTuples }
            .forEach { live ->
                val delete = handle.createUpdate(codec.buildDeleteByPrimaryKey(spec)).bind("tk", tenantKey)
                spec.primaryKey.forEach { col -> delete.bind("k_$col", live[col]) }
                delete.execute()
            }
    }

    private fun mergeBlobs(
        handle: Handle,
        manifest: TenantBackupManifest,
        blobBytes: Map<String, ByteArray>,
        tenantKey: String,
    ): Int {
        val prefix = TenantTableTopology.assetBlobPrefix(tenantKey)
        val liveKeys =
            handle
                .createQuery("SELECT key FROM content_store WHERE left(key, :len) = :prefix")
                .bind("len", prefix.length)
                .bind("prefix", prefix)
                .mapTo(String::class.java)
                .set()
        val backupKeys = manifest.blobs.map { it.key }.toSet()

        (liveKeys - backupKeys).forEach { key ->
            handle.createUpdate("DELETE FROM content_store WHERE key = :key").bind("key", key).execute()
        }

        manifest.blobs.forEach { blob ->
            val bytes = blobBytes[blob.file] ?: error("Backup archive is missing blob file ${blob.file}")
            handle
                .createUpdate(
                    "INSERT INTO content_store (key, content, content_type, size_bytes, created_at) " +
                        "VALUES (:key, :content, :contentType, :sizeBytes, :createdAt::timestamptz) " +
                        "ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, " +
                        "content_type = EXCLUDED.content_type, size_bytes = EXCLUDED.size_bytes, " +
                        "created_at = EXCLUDED.created_at",
                ).bind("key", blob.key)
                .bind("content", bytes)
                .bind("contentType", blob.contentType)
                .bind("sizeBytes", blob.sizeBytes)
                .bind("createdAt", blob.createdAt)
                .execute()
        }
        return manifest.blobs.size
    }

    /** Re-point the tenant default theme if the backup had one and that theme now exists. */
    private fun reapplyDefaultTheme(
        handle: Handle,
        tenantKey: String,
        catalogKey: Any?,
        themeKey: Any?,
    ) {
        if (catalogKey == null || themeKey == null) return
        val exists =
            handle
                .createQuery(
                    "SELECT 1 FROM themes WHERE tenant_key = :tk AND catalog_key = :ck AND id = :id",
                ).bind("tk", tenantKey)
                .bind("ck", catalogKey.toString())
                .bind("id", themeKey.toString())
                .mapTo(Int::class.java)
                .findOne()
                .isPresent
        if (!exists) return
        handle
            .createUpdate(
                "UPDATE tenants SET default_theme_catalog_key = :ck, default_theme_key = :id WHERE id = :tk",
            ).bind("tk", tenantKey)
            .bind("ck", catalogKey.toString())
            .bind("id", themeKey.toString())
            .execute()
    }

    private fun BackupTableEntry.toSpec(): TableSpec = TableSpec(
        table = table,
        columns = columns.map { ColumnMeta(it.name, it.dataType, it.udtName, it.nullable) },
        primaryKey = primaryKey,
    )
}
