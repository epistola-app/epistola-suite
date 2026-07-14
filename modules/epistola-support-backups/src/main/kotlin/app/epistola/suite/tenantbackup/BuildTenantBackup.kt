package app.epistola.suite.tenantbackup

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenantbackup.dump.DumpTenantTables
import app.epistola.suite.tenantbackup.schema.RestoreCompatibility
import app.epistola.suite.tenantbackup.schema.SchemaStamp
import app.epistola.suite.tenantbackup.schema.TenantTableTopology
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a full-fidelity, identity-preserving backup of one tenant's authoring data — every
 * INCLUDE table (full version history, exact version numbers, drafts and all) plus asset blobs —
 * serialized by the reflective dump primitive and encrypted at rest.
 *
 * This is the faithful counterpart to the catalog *export* snapshot (`BuildTenantSnapshot`), which
 * is a publishing format that keeps only the latest published version and renumbers on import. A
 * backup is restored with [RestoreTenantBackup] and is gated to a compatible schema version.
 *
 * [skipIfFingerprint] is the dedup short-circuit: when set and the freshly-dumped content fingerprint
 * equals it, the build returns **null** without zipping or encrypting the archive — so an unchanged
 * tenant (the daily-scheduler steady state) skips the expensive archive build. With it null (the
 * default) the build always produces an artifact.
 *
 * Requires [Permission.BACKUP_CREATE] — the artifact contains all of a tenant's data including
 * API-key hashes and encrypted credentials.
 */
data class BuildTenantBackup(
    override val tenantKey: TenantKey,
    val skipIfFingerprint: String? = null,
) : Command<TenantBackupArtifact?>,
    RequiresPermission {
    override val permission get() = Permission.BACKUP_CREATE
}

@Component
class BuildTenantBackupHandler(
    private val jdbi: Jdbi,
    private val topology: TenantTableTopology,
    private val dumpTenantTables: DumpTenantTables,
    private val crypto: TenantBackupCrypto,
    private val objectMapper: ObjectMapper,
    private val restoreCompatibility: RestoreCompatibility,
    private val buildProperties: BuildProperties?,
) : CommandHandler<BuildTenantBackup, TenantBackupArtifact?> {
    private val buildVersion: String get() = buildProperties?.version ?: "dev"

    @Transactional(readOnly = true)
    override fun handle(command: BuildTenantBackup): TenantBackupArtifact? = jdbi.withHandle<TenantBackupArtifact?, Exception> { handle ->
        val resolved = topology.resolve(handle)
        val schemaStamp = SchemaStamp.current(handle)
        val dump = dumpTenantTables.dump(handle, resolved, command.tenantKey.value)

        // Dedup short-circuit: the fingerprint comes from the dump alone, so an unchanged tenant skips
        // the expensive zip + encrypt below (see [BuildTenantBackup.skipIfFingerprint]).
        val fingerprint = fingerprint(dump)
        if (command.skipIfFingerprint == fingerprint) {
            return@withHandle null
        }

        val tableEntries = mutableListOf<BackupTableEntry>()
        val tableFiles = LinkedHashMap<String, ByteArray>()
        dump.tables.forEachIndexed { ordinal, table ->
            val path = "tables/${ordinal}_${table.spec.table}.jsonl"
            tableFiles[path] = encodeJsonl(table.rows)
            tableEntries.add(
                BackupTableEntry(
                    table = table.spec.table,
                    ordinal = ordinal,
                    rowCount = table.rows.size,
                    path = path,
                    primaryKey = table.spec.primaryKey,
                    columns =
                    table.spec.columns.map {
                        BackupColumn(it.name, it.dataType, it.udtName, it.nullable)
                    },
                ),
            )
        }

        val blobFiles = LinkedHashMap<String, ByteArray>()
        val blobEntries =
            dump.blobs.mapIndexed { index, blob ->
                val file = "blobs/$index.bin"
                blobFiles[file] = blob.content
                BackupBlobEntry(blob.scope, blob.contentHash, file, blob.contentType, blob.sizeBytes, blob.createdAt)
            }

        val capturedAt = EpistolaClock.instant()
        val manifest =
            TenantBackupManifest(
                formatVersion = FORMAT_VERSION,
                schemaStamp = schemaStamp,
                buildVersion = buildVersion,
                tenantKey = command.tenantKey.value,
                createdAt = capturedAt.toString(),
                fingerprint = fingerprint,
                tables = tableEntries,
                blobs = blobEntries,
                appliedMigrations = recordAppliedMigrations(handle),
            )

        val archive = buildArchive(manifest, tableFiles, blobFiles)
        TenantBackupArtifact(
            tenantKey = command.tenantKey,
            schemaStamp = schemaStamp,
            buildVersion = buildVersion,
            fingerprint = manifest.fingerprint,
            capturedAt = capturedAt,
            tableCount = tableEntries.size,
            rowCount = tableEntries.sumOf { it.rowCount },
            blobCount = blobEntries.size,
            bytes = crypto.wrap(archive),
        )
    }

    private fun encodeJsonl(rows: List<Map<String, Any?>>): ByteArray = rows.joinToString("\n") { objectMapper.writeValueAsString(it) }.toByteArray(Charsets.UTF_8)

    /**
     * Snapshots every applied migration version with its restore-compatibility flags (from
     * each migration's `backup-restore-compatibility` header), so a future **forward** restore — onto an app that can't
     * see migrations newer than itself — can read them. Records *all* applied versions (unlisted →
     * `false,false`) so an unlisted crossed migration correctly reads as not forward-compatible.
     */
    private fun recordAppliedMigrations(handle: org.jdbi.v3.core.Handle): List<BackupMigration> = handle
        .createQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY version")
        .mapTo(String::class.java)
        .list()
        .map { version ->
            val flags = restoreCompatibility.flagsFor(version)
            BackupMigration(version = version, backward = flags.backward, forward = flags.forward)
        }

    /**
     * Content fingerprint for dedup: SHA-256 over the ordered tables and rows (each row's columns
     * sorted, excluding the trigger-managed `updated_at`) plus blob keys/sizes/bytes. Excluding
     * `updated_at` keeps a no-op row touch from defeating the "unchanged since last backup" skip.
     */
    private fun fingerprint(dump: app.epistola.suite.tenantbackup.dump.TenantDump): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dump.tables.forEach { table ->
            digest.update("T:${table.spec.table}\n".toByteArray(Charsets.UTF_8))
            table.rows.forEach { row ->
                val canonical = TreeMap(row.filterKeys { it != "updated_at" })
                digest.update(objectMapper.writeValueAsBytes(canonical))
                digest.update('\n'.code.toByte())
            }
        }
        dump.blobs.forEach { blob ->
            digest.update("B:${blob.scope}:${blob.contentHash}:${blob.sizeBytes}\n".toByteArray(Charsets.UTF_8))
            digest.update(blob.content)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildArchive(
        manifest: TenantBackupManifest,
        tableFiles: Map<String, ByteArray>,
        blobFiles: Map<String, ByteArray>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest))
            zip.closeEntry()
            (tableFiles + blobFiles).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private companion object {
        const val FORMAT_VERSION = 2
    }
}
