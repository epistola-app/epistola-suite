package app.epistola.suite.backups

import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Restores a tenant from a snapshot archive. **Destructive**: every existing catalog for the
 * tenant is deleted and replaced with the snapshot's contents.
 *
 * The whole operation runs in one transaction (`@Transactional`; JDBI joins it via the
 * Spring connection factory), so a failure rolls the tenant back to its pre-restore state. The
 * sequence:
 *  1. **Wipe** — delete `variant_attribute_definitions` first (the attribute→code-list
 *     cross-catalog FK is `ON DELETE RESTRICT` and would otherwise block a bulk catalog delete),
 *     then `DELETE FROM catalogs` (every other resource cascades).
 *  2. **Reinstall the system catalog** — the wipe removed it; restored catalogs may reference it,
 *     and it must be the *current* bundled version, so it is reinstalled from the classpath.
 *  3. **Import** each snapshot catalog in cross-catalog dependency order (a catalog providing a
 *     theme/code list/font/stencil another references is imported first) with REPLACE semantics and
 *     the cross-catalog dependency pre-check disabled (the snapshot is self-consistent and atomic;
 *     the DB FKs still enforce integrity).
 */
data class RestoreTenantSnapshot(
    override val tenantKey: TenantKey,
    val archiveBytes: ByteArray,
) : Command<RestoreResult>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestoreTenantSnapshot) return false
        return tenantKey == other.tenantKey && archiveBytes.contentEquals(other.archiveBytes)
    }

    override fun hashCode(): Int = 31 * tenantKey.hashCode() + archiveBytes.contentHashCode()
}

data class RestoreResult(
    val restoredCatalogKeys: List<String>,
)

@Component
class RestoreTenantSnapshotHandler(
    private val objectMapper: ObjectMapper,
    private val jdbi: Jdbi,
) : CommandHandler<RestoreTenantSnapshot, RestoreResult> {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun handle(command: RestoreTenantSnapshot): RestoreResult = CatalogImportContext.runAsImport {
        val (manifest, innerZips) = parseArchive(command.archiveBytes)

        wipeTenant(command.tenantKey)
        InstallSystemCatalog(command.tenantKey).execute()

        val ordered = topologicalOrder(manifest.catalogs)
        for (entry in ordered) {
            val zipBytes =
                innerZips[entry.zipPath]
                    ?: throw IllegalArgumentException("Snapshot archive is missing ${entry.zipPath}")
            ImportCatalogZip(
                tenantKey = command.tenantKey,
                zipBytes = zipBytes,
                catalogType = CatalogType.valueOf(entry.type),
                authoredMode = AuthoredImportMode.REPLACE,
                validateCrossCatalogDeps = false,
            ).execute()
        }

        logger.info("Restored tenant {} from snapshot ({} catalogs)", command.tenantKey.value, ordered.size)
        RestoreResult(restoredCatalogKeys = ordered.map { it.catalogKey })
    }

    private fun wipeTenant(tenantKey: TenantKey) {
        jdbi.useHandle<Exception> { handle ->
            // Remove the rows that hold the RESTRICT cross-catalog code-list FK before the bulk
            // catalog delete; everything else cascades from `catalogs`.
            handle
                .createUpdate("DELETE FROM variant_attribute_definitions WHERE tenant_key = :tenantKey")
                .bind("tenantKey", tenantKey)
                .execute()
            handle
                .createUpdate("DELETE FROM catalogs WHERE tenant_key = :tenantKey")
                .bind("tenantKey", tenantKey)
                .execute()
        }
    }

    /**
     * Orders catalogs so every cross-catalog dependency is imported before its dependents
     * (Kahn's algorithm, deterministic by key). Throws on a dependency cycle.
     */
    private fun topologicalOrder(entries: List<SnapshotCatalogEntry>): List<SnapshotCatalogEntry> {
        val byKey = entries.associateBy { it.catalogKey }
        val remaining = byKey.toMutableMap()
        val unmet =
            entries
                .associate { e -> e.catalogKey to e.dependsOnCatalogKeys.filter { byKey.containsKey(it) }.toMutableSet() }
                .toMutableMap()

        val ordered = mutableListOf<SnapshotCatalogEntry>()
        while (remaining.isNotEmpty()) {
            val nextKey =
                remaining.keys.filter { unmet[it]!!.isEmpty() }.minOrNull()
                    ?: throw IllegalStateException(
                        "Cyclic cross-catalog dependencies in snapshot: ${remaining.keys.sorted()}",
                    )
            ordered += remaining.remove(nextKey)!!
            unmet.remove(nextKey)
            unmet.values.forEach { it.remove(nextKey) }
        }
        return ordered
    }

    private fun parseArchive(bytes: ByteArray): Pair<SnapshotManifest, Map<String, ByteArray>> {
        var manifest: SnapshotManifest? = null
        val innerZips = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val content = zip.readBytes()
                    when {
                        entry.name == "snapshot.json" ->
                            manifest = objectMapper.readValue(content, SnapshotManifest::class.java)
                        entry.name.startsWith("catalogs/") -> innerZips[entry.name] = content
                    }
                }
                entry = zip.nextEntry
            }
        }
        return (manifest ?: throw IllegalArgumentException("Snapshot archive is missing snapshot.json")) to innerZips
    }
}
