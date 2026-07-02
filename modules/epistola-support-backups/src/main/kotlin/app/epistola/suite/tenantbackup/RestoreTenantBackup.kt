package app.epistola.suite.tenantbackup

import app.epistola.suite.common.AuditDetailed
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenantbackup.restore.MergeRestoreTables
import app.epistola.suite.tenantbackup.schema.Compatibility
import app.epistola.suite.tenantbackup.schema.RestoreCompatibility
import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Restores a tenant from a faithful backup artifact ([BuildTenantBackup]). It is an **undo**, not a
 * forward migration: a cross-schema restore is allowed only when every migration crossed between the
 * backup and the running schema is declared compatible (see
 * [app.epistola.suite.tenantbackup.schema.RestoreCompatibility]); otherwise it is refused before any
 * write. The backed-up tables must also still be structurally identical (`validateColumns`).
 *
 * The whole operation is one transaction (`@Transactional`; JDBI joins it), so a failure rolls the
 * tenant back. Unlike the catalog-export restore, this is a **merge** (see [MergeRestoreTables]): it
 * preserves exact version numbers and never blanket-deletes, so document/generation history pinned
 * to unchanged versions survives. The tenant row is updated in place; it is never deleted.
 *
 * Requires [Permission.TENANT_RESTORE].
 */
data class RestoreTenantBackup(
    override val tenantKey: TenantKey,
    val artifactBytes: ByteArray,
    /** Id of the stored backup being restored — recorded in the audit trail (not used by the restore). */
    val backupId: String? = null,
) : Command<TenantRestoreResult>,
    RequiresPermission,
    AuditDetailed {
    override val permission get() = Permission.TENANT_RESTORE

    override val auditDetails: Map<String, String> get() = backupId?.let { mapOf("backupId" to it) } ?: emptyMap()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestoreTenantBackup) return false
        return tenantKey == other.tenantKey && backupId == other.backupId && artifactBytes.contentEquals(other.artifactBytes)
    }

    override fun hashCode(): Int = 31 * (31 * tenantKey.hashCode() + (backupId?.hashCode() ?: 0)) + artifactBytes.contentHashCode()
}

data class TenantRestoreResult(
    val tablesRestored: Int,
    val rowsRestored: Int,
    val blobsRestored: Int,
)

@Component
class RestoreTenantBackupHandler(
    private val jdbi: Jdbi,
    private val topology: TenantTableTopology,
    private val merge: MergeRestoreTables,
    private val crypto: TenantBackupCrypto,
    private val objectMapper: ObjectMapper,
    private val restoreCompatibility: RestoreCompatibility,
) : CommandHandler<RestoreTenantBackup, TenantRestoreResult> {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun handle(command: RestoreTenantBackup): TenantRestoreResult {
        val archive = crypto.unwrap(command.artifactBytes)
        val parsed = parseArchive(archive)
        val manifest = parsed.manifest

        // Validate everything cheap BEFORE the merge so a bad artifact never mutates the tenant.
        require(manifest.formatVersion <= FORMAT_VERSION) {
            "Unsupported backup format version ${manifest.formatVersion} (this suite supports up to $FORMAT_VERSION)"
        }
        require(manifest.tenantKey == command.tenantKey.value) {
            "Backup belongs to tenant '${manifest.tenantKey}', not '${command.tenantKey.value}'"
        }

        return jdbi.withHandle<TenantRestoreResult, Exception> { handle ->
            when (val compatibility = restoreCompatibility.check(handle, manifest.schemaStamp, manifest.appliedMigrations)) {
                is Compatibility.Compatible -> Unit
                is Compatibility.Incompatible -> throw IncompatibleBackupSchemaException(compatibility.reason)
            }
            validateColumns(handle, manifest)

            val result = merge.merge(handle, manifest, parsed.rowsByTable, parsed.blobBytes, command.tenantKey.value)
            logger.info(
                "Restored tenant {} from backup ({} tables, {} rows, {} blobs)",
                command.tenantKey.value,
                result.tablesRestored,
                result.rowsRestored,
                result.blobsRestored,
            )
            TenantRestoreResult(result.tablesRestored, result.rowsRestored, result.blobsRestored)
        }
    }

    /**
     * Belt-and-braces alongside the compatibility gate: every backed-up table must still exist with
     * the exact same column set (name + udt). Catches a structural change (a column added/dropped/
     * retyped on a backed-up table) that the declared compatibility flags intentionally do not cover —
     * the flags relax the *stamp*, never the structure. Surfaced as [IncompatibleBackupSchemaException]
     * so the UI explains it as a schema-incompatibility rather than a generic failure.
     */
    private fun validateColumns(
        handle: org.jdbi.v3.core.Handle,
        manifest: TenantBackupManifest,
    ) {
        val live = topology.resolve(handle).orderedTables.associateBy { it.table }
        manifest.tables.forEach { entry ->
            val liveSpec =
                live[entry.table]
                    ?: throw IncompatibleBackupSchemaException(
                        "table '${entry.table}' in the backup no longer exists in this schema",
                    )
            val liveCols = liveSpec.columns.associate { it.name to it.udtName }
            val backupCols = entry.columns.associate { it.name to it.udtName }
            if (liveCols != backupCols) {
                throw IncompatibleBackupSchemaException(
                    "the schema structure changed for table '${entry.table}' (column set differs) since this backup was taken",
                )
            }
        }
    }

    private fun parseArchive(bytes: ByteArray): ParsedArchive {
        var manifestBytes: ByteArray? = null
        val tableFiles = mutableMapOf<String, ByteArray>()
        val blobBytes = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val content = zip.readBytes()
                    when {
                        entry.name == "backup.json" -> manifestBytes = content
                        entry.name.startsWith("tables/") -> tableFiles[entry.name] = content
                        entry.name.startsWith("blobs/") -> blobBytes[entry.name] = content
                    }
                }
                entry = zip.nextEntry
            }
        }
        val manifest =
            objectMapper.readValue(
                manifestBytes ?: throw IllegalArgumentException("Backup archive is missing backup.json"),
                TenantBackupManifest::class.java,
            )
        val rowsByTable =
            manifest.tables.associate { entry ->
                entry.table to parseJsonl(tableFiles[entry.path] ?: ByteArray(0))
            }
        return ParsedArchive(manifest, rowsByTable, blobBytes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonl(bytes: ByteArray): List<Map<String, Any?>> = String(bytes, Charsets.UTF_8)
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }
        .toList()

    private data class ParsedArchive(
        val manifest: TenantBackupManifest,
        val rowsByTable: Map<String, List<Map<String, Any?>>>,
        val blobBytes: Map<String, ByteArray>,
    )

    private companion object {
        const val FORMAT_VERSION = 2
    }
}
