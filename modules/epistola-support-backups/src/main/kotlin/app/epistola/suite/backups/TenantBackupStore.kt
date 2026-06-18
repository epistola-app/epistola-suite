package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.tenantbackup.TenantBackupArtifact
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Metadata for one stored backup, surfaced to the Backups UI (newest first). */
data class StoredBackup(
    val id: String,
    val fingerprint: String,
    val schemaStamp: String,
    val buildVersion: String,
    val tableCount: Int,
    val rowCount: Int,
    val blobCount: Int,
    val sizeBytes: Long,
    val capturedAt: Instant,
    val createdAt: Instant,
)

/**
 * Storage for faithful tenant backup artifacts — a **port** so a hub-backed transport can replace
 * the local store later without touching the scheduler/UI. The current implementation
 * ([JdbiTenantBackupStore]) keeps artifacts in the `tenant_backups` table: backups exist to correct
 * mistakes, so the same database the tenant lives in is the right scope.
 */
interface TenantBackupStore {
    fun save(artifact: TenantBackupArtifact): String

    fun list(tenantKey: TenantKey): List<StoredBackup>

    fun load(
        tenantKey: TenantKey,
        backupId: String,
    ): ByteArray?

    /** Deletes all but the [keep] newest backups for a tenant; returns the number deleted. */
    fun pruneToRetention(
        tenantKey: TenantKey,
        keep: Int,
    ): Int
}

/**
 * Local Postgres store (`tenant_backups` table) — the default. Used in OSS deployments and whenever
 * the support tier is off; when `epistola.support.enabled=true` the [HubTenantBackupStore] replaces
 * it so backups ride to the hub instead. The two are mutually exclusive by property.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class JdbiTenantBackupStore(
    private val jdbi: Jdbi,
) : TenantBackupStore {
    override fun save(artifact: TenantBackupArtifact): String = jdbi.withHandle<String, Exception> { handle ->
        val id = UUID.randomUUID().toString()
        handle
            .createUpdate(
                "INSERT INTO tenant_backups (id, tenant_key, fingerprint, schema_stamp, build_version, " +
                    "table_count, row_count, blob_count, size_bytes, content, captured_at) " +
                    "VALUES (:id::uuid, :tenantKey, :fingerprint, :schemaStamp, :buildVersion, " +
                    ":tableCount, :rowCount, :blobCount, :sizeBytes, :content, :capturedAt)",
            ).bind("id", id)
            .bind("tenantKey", artifact.tenantKey)
            .bind("fingerprint", artifact.fingerprint)
            .bind("schemaStamp", artifact.schemaStamp)
            .bind("buildVersion", artifact.buildVersion)
            .bind("tableCount", artifact.tableCount)
            .bind("rowCount", artifact.rowCount)
            .bind("blobCount", artifact.blobCount)
            .bind("sizeBytes", artifact.bytes.size.toLong())
            .bind("content", artifact.bytes)
            .bind("capturedAt", EpistolaClock.offsetDateTime().withOffsetSameInstant(java.time.ZoneOffset.UTC))
            .execute()
        id
    }

    override fun list(tenantKey: TenantKey): List<StoredBackup> = jdbi.withHandle<List<StoredBackup>, Exception> { handle ->
        handle
            .createQuery(
                "SELECT id, fingerprint, schema_stamp, build_version, table_count, row_count, blob_count, " +
                    "size_bytes, captured_at, created_at FROM tenant_backups " +
                    "WHERE tenant_key = :tenantKey ORDER BY captured_at DESC",
            ).bind("tenantKey", tenantKey)
            .map { rs, _ ->
                StoredBackup(
                    id = rs.getString("id"),
                    fingerprint = rs.getString("fingerprint"),
                    schemaStamp = rs.getString("schema_stamp"),
                    buildVersion = rs.getString("build_version"),
                    tableCount = rs.getInt("table_count"),
                    rowCount = rs.getInt("row_count"),
                    blobCount = rs.getInt("blob_count"),
                    sizeBytes = rs.getLong("size_bytes"),
                    capturedAt = rs.getObject("captured_at", java.time.OffsetDateTime::class.java).toInstant(),
                    createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }

    override fun load(
        tenantKey: TenantKey,
        backupId: String,
    ): ByteArray? = jdbi.withHandle<ByteArray?, Exception> { handle ->
        handle
            .createQuery("SELECT content FROM tenant_backups WHERE tenant_key = :tenantKey AND id = :id::uuid")
            .bind("tenantKey", tenantKey)
            .bind("id", backupId)
            .map { rs, _ -> rs.getBytes("content") }
            .findOne()
            .orElse(null)
    }

    override fun pruneToRetention(
        tenantKey: TenantKey,
        keep: Int,
    ): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle
            .createUpdate(
                "DELETE FROM tenant_backups WHERE tenant_key = :tenantKey AND id NOT IN (" +
                    "SELECT id FROM tenant_backups WHERE tenant_key = :tenantKey " +
                    "ORDER BY captured_at DESC LIMIT :keep)",
            ).bind("tenantKey", tenantKey)
            .bind("keep", keep)
            .execute()
    }
}
