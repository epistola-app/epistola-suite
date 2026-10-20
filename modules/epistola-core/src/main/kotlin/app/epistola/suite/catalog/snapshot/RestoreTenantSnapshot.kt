// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.snapshot

import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.AuthoredImportMode
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.PurgeTenantCatalogs
import app.epistola.suite.catalog.commands.PurgeTenantCatalogsResult
import app.epistola.suite.catalog.identity.TenantResourceIdentities
import app.epistola.suite.catalog.identity.TenantResourceIdentityStore
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.catalog.versions.CatalogDrafts
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
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
 * The whole operation runs in one transaction (`@Transactional`; JDBI joins it via the Spring
 * connection factory), so a failure rolls the tenant back to its pre-restore state. The sequence:
 *  1. **Validate the archive before touching anything** — supported schema version, the snapshot
 *     belongs to this tenant, and every catalog's bytes are present.
 *  2. **Purge** the tenant's catalogs via the core [PurgeTenantCatalogs] command (core owns the
 *     FK-aware deletion; this module does not encode the catalog schema's FK topology).
 *  3. **Reinstall the system catalog** (excluded from snapshots; reinstalled from the classpath).
 *  4. **Import** each snapshot catalog in cross-catalog dependency order with REPLACE semantics and
 *     the cross-catalog dependency pre-check disabled (the snapshot is self-consistent and atomic;
 *     the DB FKs still enforce integrity).
 *  5. **Re-apply** the tenant's prior default theme if that theme exists again after the import.
 */
data class RestoreTenantSnapshot(
    override val tenantKey: TenantKey,
    val archiveBytes: ByteArray,
) : Command<RestoreResult>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_RESTORE

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
    private val identityStore: TenantResourceIdentityStore,
    private val jdbi: Jdbi,
    private val schemaMigrator: CatalogSchemaMigrator,
    private val drafts: CatalogDrafts,
) : CommandHandler<RestoreTenantSnapshot, RestoreResult> {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun handle(command: RestoreTenantSnapshot): RestoreResult = CatalogImportContext.runAsImport {
        val (manifest, identities, innerZips) = parseArchive(command.archiveBytes)

        // Validate fully BEFORE the destructive purge — a bad archive must not leave the tenant wiped.
        require(manifest.schemaVersion <= SUPPORTED_SCHEMA_VERSION) {
            "Unsupported snapshot schema version ${manifest.schemaVersion} (this suite supports up to $SUPPORTED_SCHEMA_VERSION)"
        }
        require(manifest.tenantKey == command.tenantKey.value) {
            "Snapshot belongs to tenant '${manifest.tenantKey}', not '${command.tenantKey.value}'"
        }
        val ordered = topologicalOrder(manifest.catalogs)
        ordered.forEach { entry ->
            require(innerZips.containsKey(entry.zipPath)) { "Snapshot archive is missing ${entry.zipPath}" }
        }

        val prior = PurgeTenantCatalogs(command.tenantKey).execute()
        InstallSystemCatalog(command.tenantKey).execute()

        // The catalogs first, named as their archives name them: a resource identity belongs to a
        // catalog, so the identities can only be planted once their catalogs exist.
        ordered.forEach { entry -> createCatalogRow(command.tenantKey, entry, innerZips.getValue(entry.zipPath)) }

        // Before the import, so the sync trigger adopts each recorded identity instead of minting a
        // fresh one. Restoring with new identities would dangle the template_resource_id on every
        // generation record (no foreign key protects it, so it just stops joining).
        identityStore.plantIdentities(command.tenantKey, identities.resources)

        for (entry in ordered) {
            ImportCatalogZip(
                tenantKey = command.tenantKey,
                zipBytes = innerZips.getValue(entry.zipPath),
                catalogType = CatalogType.valueOf(entry.type),
                authoredMode = AuthoredImportMode.REPLACE,
                validateCrossCatalogDeps = false,
                restore = true,
            ).execute()
        }

        reapplyDefaultTheme(command.tenantKey, prior)

        logger.info("Restored tenant {} from snapshot ({} catalogs)", command.tenantKey.value, ordered.size)
        RestoreResult(restoredCatalogKeys = ordered.map { it.catalogKey })
    }

    /**
     * The catalog row for [entry], with the registry and namespace its archive records; the import
     * that follows fills it. A catalog that already exists (the system catalog) is left alone.
     */
    private fun createCatalogRow(tenantKey: TenantKey, entry: SnapshotCatalogEntry, zip: ByteArray) {
        val catalog = schemaMigrator.migrateAndBindManifest(catalogJson(zip), tenantKey.value).manifest.catalog
        jdbi.useHandle<Exception> { handle ->
            val inserted = handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, type)
                VALUES (:catalogKey, :tenantKey, :registry, :namespace, :name, :type)
                ON CONFLICT (tenant_key, catalog_key) DO NOTHING
                """,
            )
                .bind("catalogKey", entry.catalogKey)
                .bind("tenantKey", tenantKey)
                .bind("registry", requireNotNull(catalog.registry) { "Snapshot catalog ${entry.catalogKey} names no registry" })
                .bind("namespace", requireNotNull(catalog.namespace) { "Snapshot catalog ${entry.catalogKey} names no namespace" })
                .bind("name", entry.name)
                .bind("type", entry.type)
                .execute()
            // A restore keeps no releases, so an authored catalog's next version starts again where a
            // new catalog's does, on no release.
            if (inserted > 0 && entry.type == CatalogType.AUTHORED.name) {
                drafts.open(handle, tenantKey, CatalogKey.of(entry.catalogKey), base = null)
            }
        }
    }

    private fun catalogJson(zip: ByteArray): ByteArray = java.util.zip.ZipInputStream(zip.inputStream()).use { stream ->
        generateSequence { stream.nextEntry }.firstOrNull { it.name == "catalog.json" }
            ?: throw IllegalArgumentException("Snapshot catalog archive has no catalog.json")
        stream.readAllBytes()
    }

    /** Re-point the tenant's default theme (cleared by the purge) if it exists in the restored catalogs. */
    private fun reapplyDefaultTheme(
        tenantKey: TenantKey,
        prior: PurgeTenantCatalogsResult,
    ) {
        val catalogKey = prior.priorDefaultThemeCatalogKey ?: return
        val themeKey = prior.priorDefaultThemeKey ?: return
        try {
            SetTenantDefaultTheme(
                tenantId = tenantKey,
                themeId = ThemeKey.of(themeKey),
                catalogKey = CatalogKey.of(catalogKey),
            ).execute()
        } catch (e: Exception) {
            // The default theme's catalog/theme may not be in the snapshot — leave it cleared.
            logger.warn("Could not re-apply default theme {}/{} after restore: {}", catalogKey, themeKey, e.message)
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

    private data class ParsedArchive(
        val manifest: SnapshotManifest,
        val identities: TenantResourceIdentities,
        val innerZips: Map<String, ByteArray>,
    )

    private fun parseArchive(bytes: ByteArray): ParsedArchive {
        var manifest: SnapshotManifest? = null
        // Absent from a schema-1 archive, which restores with fresh identities exactly as it did
        // when it was written.
        var identities = TenantResourceIdentities()
        val innerZips = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val content = zip.readBytes()
                    when {
                        entry.name == "snapshot.json" ->
                            manifest = objectMapper.readValue(content, SnapshotManifest::class.java)

                        entry.name == "identities.json" ->
                            identities = objectMapper.readValue(content, TenantResourceIdentities::class.java)

                        entry.name.startsWith("catalogs/") -> innerZips[entry.name] = content
                    }
                }
                entry = zip.nextEntry
            }
        }
        return ParsedArchive(
            manifest ?: throw IllegalArgumentException("Snapshot archive is missing snapshot.json"),
            identities,
            innerZips,
        )
    }

    private companion object {
        /** Highest snapshot manifest schema version this suite can restore. */
        const val SUPPORTED_SCHEMA_VERSION = 2
    }
}
