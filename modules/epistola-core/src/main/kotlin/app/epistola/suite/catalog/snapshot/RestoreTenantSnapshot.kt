// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.snapshot

import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.DraftFillMode
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.PurgeTenantCatalogs
import app.epistola.suite.catalog.commands.PurgeTenantCatalogsResult
import app.epistola.suite.catalog.identity.TenantResourceIdentities
import app.epistola.suite.catalog.identity.TenantResourceIdentityStore
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
            val restored = requireNotNull(app.epistola.suite.catalog.queries.GetCatalog(command.tenantKey, CatalogKey.of(entry.catalogKey)).query())
            // Its releases first, lowest first, each recorded beside as it says it is (schema 3), so
            // the next version the working copy fills is not moved onto them.
            for (release in entry.releases) {
                val zip = release.zipPath?.let(innerZips::get)
                if (zip == null) {
                    logger.warn("Snapshot release {} {} carries no archive; not restored", entry.catalogKey, release.version)
                    continue
                }
                ImportCatalogZip(
                    tenantKey = command.tenantKey,
                    zipBytes = zip,
                    validateCrossCatalogDeps = false,
                    confirmMigration = true,
                    legacyName = app.epistola.suite.catalog.commands.LegacyCatalogName(restored.registry, restored.namespace),
                    restoreRelease = true,
                ).execute()
            }
            ImportCatalogZip(
                tenantKey = command.tenantKey,
                zipBytes = innerZips.getValue(entry.zipPath),
                fillMode = DraftFillMode.REPLACE,
                validateCrossCatalogDeps = false,
                restore = true,
                // An archive from before wire v8 is named as its catalog row was created above.
                legacyName = app.epistola.suite.catalog.commands.LegacyCatalogName(restored.registry, restored.namespace),
            ).execute()
        }

        ordered.forEach { entry -> restoreReleaseState(command.tenantKey, entry) }

        reapplyDefaultTheme(command.tenantKey, prior)

        logger.info("Restored tenant {} from snapshot ({} catalogs)", command.tenantKey.value, ordered.size)
        RestoreResult(restoredCatalogKeys = ordered.map { it.catalogKey })
    }

    /**
     * The catalog row for [entry], with the registry and namespace its archive records; the import
     * that follows fills its next version, which the database opens with the row on no release; its
     * releases, and the release the next version is on, are restored after (schema 3). The row
     * gets the catalog's source, without a credential. A catalog that already exists (the system
     * catalog) is left alone.
     * The entry's `type`, which snapshots still carry for the hub (ADR 0032), is not read.
     */
    private fun createCatalogRow(tenantKey: TenantKey, entry: SnapshotCatalogEntry, zip: ByteArray) {
        val catalog = schemaMigrator.migrateAndBindManifest(catalogJson(zip), tenantKey.value).manifest.catalog
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, source_url, source_auth_type)
                VALUES (:catalogKey, :tenantKey, :registry, :namespace, :name, :sourceUrl, :sourceAuthType)
                ON CONFLICT (tenant_key, namespace, catalog_key) DO NOTHING
                """,
            )
                .bind("catalogKey", entry.catalogKey)
                .bind("tenantKey", tenantKey)
                .bind("registry", requireNotNull(catalog.registry) { "Snapshot catalog ${entry.catalogKey} names no registry" })
                .bind("namespace", requireNotNull(catalog.namespace) { "Snapshot catalog ${entry.catalogKey} names no namespace" })
                .bind("name", entry.name)
                // Where it receives releases from (schema 3); its credential is not in a snapshot.
                .bind("sourceUrl", entry.source?.url)
                .bind("sourceAuthType", entry.source?.authType ?: "NONE")
                .execute()
        }
    }

    /**
     * What the snapshot says of a catalog's releases beyond their content (schema 3): when and by
     * whom each was released and its notes, the catalog's released and installed releases, and the
     * release its next release is on. Raw SQL: these are the rows' own columns, which no command
     * sets to a past value; the trigger on `installed_release_version` moves the next version, so
     * its base is set after.
     */
    private fun restoreReleaseState(tenantKey: TenantKey, entry: SnapshotCatalogEntry) {
        jdbi.useHandle<Exception> { handle ->
            for (release in entry.releases) {
                handle.createUpdate(
                    """
                    UPDATE catalog_releases
                    SET released_at = COALESCE(CAST(:at AS TIMESTAMPTZ), released_at), released_by = CAST(:by AS UUID), notes = :notes
                    WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v
                    """,
                )
                    .bind("t", tenantKey).bind("c", entry.catalogKey).bind("v", release.version)
                    .bind("at", release.releasedAt).bind("by", release.releasedBy).bind("notes", release.notes)
                    .execute()
            }
            if (entry.version != null) {
                handle.createUpdate(
                    """
                    UPDATE catalogs c SET released_version = r.version, released_fingerprint = r.fingerprint, released_at = r.released_at
                    FROM catalog_releases r
                    WHERE c.tenant_key = :t AND c.catalog_id = epistola_catalog_id(:t, :c) AND r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.version = :v
                    """,
                )
                    .bind("t", tenantKey).bind("c", entry.catalogKey).bind("v", entry.version)
                    .execute()
            }
            entry.installed?.let { installed ->
                handle.createUpdate(
                    "UPDATE catalogs SET installed_release_version = :v, installed_fingerprint = :f, installed_at = NOW() WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c)",
                )
                    .bind("t", tenantKey).bind("c", entry.catalogKey).bind("v", installed.version).bind("f", installed.fingerprint)
                    .execute()
            }
            entry.nextBase?.let { base ->
                handle.createUpdate(
                    """
                    UPDATE catalog_versions SET base_version = :b
                    WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND kind = 'draft' AND is_next
                      AND EXISTS (SELECT 1 FROM catalog_releases r WHERE r.tenant_key = :t AND r.catalog_id = catalog_versions.catalog_id AND r.version = :b)
                    """,
                )
                    .bind("t", tenantKey).bind("c", entry.catalogKey).bind("b", base)
                    .execute()
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
        const val SUPPORTED_SCHEMA_VERSION = 3
    }
}
