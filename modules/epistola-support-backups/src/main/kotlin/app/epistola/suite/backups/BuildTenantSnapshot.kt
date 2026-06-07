package app.epistola.suite.backups

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a single point-in-time snapshot of all of a tenant's catalogs — the shared primitive
 * behind backups and compatibility checks. Each catalog is exported with the existing
 * [ExportCatalogZip] (byte-for-byte the same as a single-catalog export) and bundled, verbatim,
 * into one outer archive alongside a [SnapshotManifest].
 *
 * The bootstrap-managed system catalog ([SYSTEM_CATALOG_KEY]) is excluded: it is reinstalled
 * from the running suite's classpath, not tenant data, so backing it up would be redundant and a
 * stale restore would be wrong. Restore reinstalls it instead.
 *
 * If a catalog cannot be exported (e.g. it pins one stencil at multiple versions, which the wire
 * format can't carry), [ExportCatalogZip] throws and the whole snapshot fails loudly — a catalog
 * is never silently dropped from a backup.
 */
data class BuildTenantSnapshot(
    override val tenantKey: TenantKey,
) : Command<TenantSnapshot>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class BuildTenantSnapshotHandler(
    private val objectMapper: ObjectMapper,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
) : CommandHandler<BuildTenantSnapshot, TenantSnapshot> {
    override fun handle(command: BuildTenantSnapshot): TenantSnapshot {
        val catalogs =
            ListCatalogs(command.tenantKey)
                .query()
                .filter { it.id != SYSTEM_CATALOG_KEY }
                .sortedBy { it.id.value }

        val entries = mutableListOf<SnapshotCatalogEntry>()
        val innerZips = LinkedHashMap<String, ByteArray>()

        for (catalog in catalogs) {
            // The stable content fingerprint (excludes release.*/timestamps), same value
            // ExportCatalogZip embeds in catalog.json — read directly so dedup is deterministic.
            val fingerprint = fingerprintService.fingerprint(contentBuilder.build(command.tenantKey, catalog.id))
            val export = ExportCatalogZip(command.tenantKey, catalog.id).execute()
            val zipPath = "catalogs/${catalog.id.value}.zip"
            innerZips[zipPath] = export.zipBytes
            entries.add(
                SnapshotCatalogEntry(
                    catalogKey = catalog.id.value,
                    name = catalog.name,
                    type = catalog.type.name,
                    catalogFingerprint = fingerprint,
                    version = catalog.releasedVersion,
                    zipPath = zipPath,
                    zipSizeBytes = export.zipBytes.size.toLong(),
                ),
            )
        }

        val snapshotFingerprint = rollUpFingerprint(entries)
        val capturedAt = Instant.now()
        val manifest =
            SnapshotManifest(
                schemaVersion = SNAPSHOT_SCHEMA_VERSION,
                tenantKey = command.tenantKey.value,
                createdAt = capturedAt.toString(),
                snapshotFingerprint = snapshotFingerprint,
                catalogs = entries,
            )

        val bytes = buildArchive(manifest, innerZips)
        return TenantSnapshot(
            tenantKey = command.tenantKey,
            snapshotFingerprint = snapshotFingerprint,
            capturedAt = capturedAt,
            catalogCount = entries.size,
            bytes = bytes,
        )
    }

    /**
     * Rolled-up tenant fingerprint: SHA-256 over the per-catalog content fingerprints, sorted by
     * catalog key. Cheap (no re-hashing of bytes), order-stable, and changes iff a catalog's
     * content changes or a catalog is added/removed.
     */
    private fun rollUpFingerprint(entries: List<SnapshotCatalogEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries
            .sortedBy { it.catalogKey }
            .forEach { digest.update("${it.catalogKey}:${it.catalogFingerprint}\n".toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildArchive(
        manifest: SnapshotManifest,
        innerZips: Map<String, ByteArray>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("snapshot.json"))
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest))
            zip.closeEntry()

            for ((path, bytes) in innerZips) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private companion object {
        const val SNAPSHOT_SCHEMA_VERSION = 1
    }
}
