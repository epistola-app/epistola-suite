// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import app.epistola.catalog.validation.ValidationSeverity
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogImportSchemaAction
import app.epistola.suite.catalog.CatalogMetadata
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DataExample
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

/**
 * Imports a catalog from a ZIP archive into a catalog of [catalogType].
 *
 * A slug that already exists with a *different* type is rejected (it would flip
 * ownership semantics). Otherwise:
 *
 * - **AUTHORED** — your editable copy. When the import *creates* the catalog
 *   and the manifest carries a clean released SemVer + fingerprint, that
 *   becomes the initial release. Importing into an **existing** AUTHORED
 *   catalog is an *edit* (release state is never touched — drift shows and the
 *   owner re-releases deliberately); [authoredMode] decides what happens to
 *   your local-only resources:
 *     - `MERGE` (default) — upsert the ZIP's resources over your copy, **keep**
 *       resources you have locally that the ZIP doesn't (overlay);
 *     - `REPLACE` — make the catalog *exactly* the ZIP: also **delete**
 *       local-only resources, conflict-checked before mutating (a pruned
 *       resource still referenced from another catalog blocks the import,
 *       same as the SUBSCRIBED path); release state still untouched.
 *   (Ignored when the import creates the catalog, or for SUBSCRIBED.)
 * - **SUBSCRIBED** — a managed mirror. The ZIP *is* the upgrade transport
 *   (instead of a source URL): full `UpgradeCatalog` parity — conflict-checked
 *   before mutating, resources replaced, stale pruned, abort-on-failed-install
 *   (no silent half-upgrade), and `installed_*` advanced from the manifest.
 */
data class ImportCatalogZip(
    override val tenantKey: TenantKey,
    val zipBytes: ByteArray,
    val catalogType: CatalogType,
    val authoredMode: AuthoredImportMode = AuthoredImportMode.MERGE,
    /**
     * Whether to pre-validate that cross-catalog dependencies (a theme / code list /
     * font / stencil this catalog references in *another* catalog) already exist.
     * `true` (default) for normal imports. A full-tenant **restore** sets this `false`:
     * the snapshot is a self-consistent whole imported atomically in one transaction, and
     * the importer orders catalogs so each dependency lands before its dependents — so the
     * per-catalog existence pre-check would spuriously fail on a not-yet-imported sibling.
     * The database FKs still enforce referential integrity regardless of this flag.
     */
    val validateCrossCatalogDeps: Boolean = true,
    /**
     * Set when the operator has confirmed updating an **AUTHORED** ZIP whose catalog
     * schema version is below current but migratable. Without it such an import is
     * not run — the handler raises [CatalogMigrationConfirmationRequiredException] so
     * the UI can prompt. The REST import sets it `true` (non-interactive).
     */
    val confirmMigration: Boolean = false,
    /**
     * Where this content came from, recorded on the catalog when the import **creates** it.
     *
     * Opaque here: the importer stores it and never resolves it. The scheme is what decides who
     * can answer "is there a newer release?" later — `https:`/`file:`/`classpath:` name a manifest
     * `CatalogClient` can fetch, and other schemes are answered by whichever `CatalogUpstreamProbe`
     * claims them.
     *
     * Null for a ZIP somebody uploaded, which is the one subscribed catalog with no upstream to
     * ask. An existing catalog never has its source rewritten by an import: a restore re-imports
     * subscribed content without knowing the source (the `catalogs` row carries it and is restored
     * separately), so a null here must leave an existing source alone rather than clear it.
     */
    val sourceUrl: String? = null,
) : Command<ImportCatalogZipResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
}

/** What an AUTHORED ZIP import does with resources you have locally but the ZIP doesn't. */
enum class AuthoredImportMode {
    /** Overlay: upsert the ZIP's resources, keep local-only ones (default). */
    MERGE,

    /** Mirror: also delete local-only resources (conflict-checked). */
    REPLACE,
}

data class ImportCatalogZipResult(
    val catalogKey: CatalogKey,
    val catalogName: String,
    val results: List<InstallResult>,
    /**
     * SUBSCRIBED only: true when a resource install FAILED, so stale resources
     * were NOT pruned and `installed_*` was NOT advanced — the catalog stays on
     * its previous release and a re-import retries (parity with `UpgradeCatalog`,
     * never a silent half-upgrade).
     */
    val aborted: Boolean = false,
)

@Component
class ImportCatalogZipHandler(
    private val objectMapper: ObjectMapper,
    private val protocolMapper: ProtocolMapper,
    private val sizeLimits: CatalogSizeLimits,
    private val jdbi: org.jdbi.v3.core.Jdbi,
    private val analyzer: CatalogUpgradeAnalyzer,
    private val schemaMigrator: CatalogSchemaMigrator,
    private val identity: CatalogIdentityProperties,
) : CommandHandler<ImportCatalogZip, ImportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    override fun handle(command: ImportCatalogZip): ImportCatalogZipResult = CatalogImportContext.runAsImport {
        // Size checks: compressed ZIP and decompressed total
        val maxZipSize = sizeLimits.maxZipSize.toBytes()
        val maxDecompressedSize = sizeLimits.maxDecompressedSize.toBytes()

        require(command.zipBytes.size <= maxZipSize) {
            "Catalog ZIP exceeds maximum size of ${sizeLimits.maxZipSize} " +
                "(actual: ${command.zipBytes.size / 1024 / 1024} MB)"
        }

        // epistola-catalog owns ZIP safety and path normalization. Suite
        // materializes the already-checked entries because the persistence
        // orchestration below consumes them more than once.
        val read = CatalogArchiveReader.read(
            ByteArrayInputStream(command.zipBytes),
            CatalogArchivePolicy(
                maxCompressedBytes = maxZipSize,
                maxExpandedBytes = maxDecompressedSize,
            ),
        )
        val migrationFinding = read.findings.firstOrNull {
            it.code == CatalogMigrationCodes.SCHEMA_UNKNOWN ||
                it.code == CatalogMigrationCodes.SCHEMA_TOO_OLD ||
                it.code == CatalogMigrationCodes.SCHEMA_TOO_NEW ||
                it.code == CatalogMigrationCodes.SCHEMA_VERSION_MISMATCH ||
                it.code == CatalogMigrationCodes.RESOURCE_TYPE_MISMATCH
        }
        if (migrationFinding != null) {
            throw schemaMigrator.asSuiteException(migrationFinding.code, migrationFinding.message)
        }
        val archive = read.archive ?: throw CatalogImportValidationException(
            read.findings.map {
                CatalogImportValidationFinding(it.code, ValidationSeverity.ERROR, it.path, it.message)
            },
        )
        val entries = archive.use { safeArchive ->
            val validation = CatalogValidator.validate(
                safeArchive,
                CatalogValidationPolicy(verifyFingerprint = true),
            )
            val validationFindings = read.findings.map {
                CatalogImportValidationFinding(it.code, ValidationSeverity.ERROR, it.path, it.message)
            } + validation.findings.map {
                CatalogImportValidationFinding(it.code, it.severity, it.path, it.message)
            }
            if (read.findings.isNotEmpty() || !validation.valid) {
                throw CatalogImportValidationException(validationFindings)
            }
            safeArchive.paths.associateWith { path ->
                safeArchive.content.open(path).use { it.readAllBytes() }
            }
        }

        // Parse manifest
        val manifestBytes = entries["catalog.json"]
            ?: throw IllegalArgumentException("ZIP does not contain catalog.json")
        // Catalog-wide version gate + wire-format upgrade chain before binding
        // (see CatalogSchemaMigrator): the manifest carries the authoritative
        // schemaVersion and every resource detail echoes it, so details are gated
        // against the same version below. The chain is empty today, so
        // current-shape payloads pass straight through to binding.
        // An archive from before wire v8 lands in the importing tenant's namespace (ADR 0003).
        val migratedManifest = schemaMigrator.migrateAndBindManifest(manifestBytes, command.tenantKey.value)
        val manifest = migratedManifest.manifest
        val catalogCtx = migratedManifest.catalog
        // Decide what to do about an outdated-schema ZIP (a source-less transport
        // we can't re-fetch). SUBSCRIBED mirrors are never migrated locally; an
        // AUTHORED ZIP with no migration path is rejected; an AUTHORED ZIP the chain
        // can upgrade prompts for confirmation (migrating mutates content) and then
        // imports. `sourceVersion` is the pre-migration version; `manifest.schemaVersion`
        // is current iff a chain upgraded it. Covers the UI and REST import paths.
        when (
            CatalogImportSchemaAction.decide(
                catalogType = command.catalogType,
                sourceVersion = catalogCtx.sourceVersion,
                migratedVersion = manifest.schemaVersion,
                current = CATALOG_SCHEMA_VERSION,
                confirmed = command.confirmMigration,
            )
        ) {
            CatalogImportSchemaAction.BLOCK_TOO_OLD ->
                throw CatalogSchemaTooOldException(catalogCtx.sourceVersion, CATALOG_SCHEMA_VERSION)

            CatalogImportSchemaAction.CONFIRM_MIGRATION ->
                throw CatalogMigrationConfirmationRequiredException(
                    catalogCtx.sourceVersion,
                    CATALOG_SCHEMA_VERSION,
                    read.migrationNotices,
                )

            CatalogImportSchemaAction.IMPORT -> {} // proceed
        }
        val catalogKey = CatalogKey.of(manifest.catalog.key)
        // #692: bound the manifest name to catalogs.name VARCHAR(255). SUBSCRIBED writes
        // it directly (insert/update); AUTHORED goes through CreateCatalog (tighter cap),
        // so this only tightens the direct-write paths that had no guard.
        validateCatalogNameLength(manifest.catalog.name)

        // A ZIP import targets a catalog *type*. A slug that already exists
        // with a different type is a conflict — importing AUTHORED content over
        // a SUBSCRIBED mirror (or vice-versa) would silently flip ownership
        // semantics. Same type is fine: AUTHORED ⇒ in-place edit; SUBSCRIBED ⇒
        // an upgrade (the ZIP is the transport instead of a source URL).
        val existingCatalog = GetCatalog(command.tenantKey, catalogKey).query()
        if (existingCatalog != null && existingCatalog.type != command.catalogType) {
            throw IllegalArgumentException(
                "Catalog '${catalogKey.value}' already exists as ${existingCatalog.type} — " +
                    "cannot import it as ${command.catalogType}.",
            )
        }

        // Re-pointing a mirror at a different upstream is never what an import means. Both sides
        // null (a plain ZIP over a ZIP-managed catalog) and an incoming null (a restore, which
        // carries the source on the restored `catalogs` row instead) stay allowed — only a
        // declared source that disagrees with the recorded one is refused.
        val existingSource = existingCatalog?.sourceUrl
        if (existingSource != null && command.sourceUrl != null && existingSource != command.sourceUrl) {
            throw IllegalArgumentException(
                "Catalog '${catalogKey.value}' is installed from '$existingSource' — " +
                    "cannot re-import it from '${command.sourceUrl}'. Remove it first.",
            )
        }

        // Validate cross-catalog dependencies exist (batch check). Skipped during a full-tenant
        // restore, which imports a self-consistent snapshot in dependency order within one
        // transaction (the DB FKs still enforce integrity).
        val dependencies = manifest.dependencies.orEmpty()
        if (command.validateCrossCatalogDeps && dependencies.isNotEmpty()) {
            val missing = findMissingDependencies(command.tenantKey, dependencies)
            if (missing.isNotEmpty()) {
                val details = missing.joinToString(", ") { dep ->
                    when (dep) {
                        is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme '${dep.key}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil '${dep.key}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Image -> "image '${dep.key}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.CodeList -> "code list '${dep.key}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Font -> "font '${dep.key}' from catalog '${dep.catalogKey}'"
                    }
                }
                throw IllegalArgumentException(
                    "Catalog '${catalogKey.value}' has unmet dependencies: $details. " +
                        "Import or create these resources first.",
                )
            }
        }

        val tenantId = TenantId(command.tenantKey)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.key })

        // Stale-prune cases: SUBSCRIBED (always — the ZIP is the upgrade), and
        // AUTHORED REPLACE into an existing catalog (mirror it exactly). Both
        // validate cross-catalog conflicts BEFORE any mutation — a stale
        // resource still referenced from another catalog blocks the whole
        // import. (New catalog ⇒ nothing installed ⇒ no stale ⇒ no conflicts;
        // AUTHORED MERGE keeps local-only resources, so never prunes.)
        val prunesStale = command.catalogType == CatalogType.SUBSCRIBED ||
            (
                command.catalogType == CatalogType.AUTHORED &&
                    existingCatalog != null &&
                    command.authoredMode == AuthoredImportMode.REPLACE
                )
        val staleBefore = if (prunesStale) {
            val installed = analyzer.installedByType(command.tenantKey, catalogKey)
            val stale = analyzer.computeStale(installed, manifestSlugs)
            if (stale.isNotEmpty()) {
                val conflicts = analyzer.findConflicts(command.tenantKey, catalogKey, stale)
                if (conflicts.isNotEmpty()) throw CatalogUpgradeConflictException(conflicts)
            }
            stale
        } else {
            emptyList()
        }

        // Ensure the catalog row exists with the requested type before install.
        if (existingCatalog == null) {
            when (command.catalogType) {
                // A catalog keeps the name its archive gives it (contract ADR 0003).
                CatalogType.AUTHORED -> CreateCatalog(
                    tenantKey = command.tenantKey,
                    id = catalogKey,
                    name = manifest.catalog.name,
                    description = manifest.catalog.description,
                    namespace = manifest.catalog.namespace,
                    registry = manifest.catalog.registry,
                ).execute()

                CatalogType.SUBSCRIBED -> insertSubscribedCatalog(
                    command.tenantKey,
                    catalogKey,
                    manifest.catalog.name,
                    manifest.catalog.description,
                    command.sourceUrl,
                    registry = manifest.catalog.registry ?: identity.defaultRegistry,
                    namespace = manifest.catalog.namespace ?: CatalogIdentityProperties.defaultNamespace(command.tenantKey),
                )
            }
        }

        val ordered = manifest.resources.sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }

        // Pre-parse every stencil detail once. Each detail is routed through the
        // migrator (catalog-wide version gate + chain) before binding; the chain
        // is empty today, so current-shape details pass through unchanged.
        // Missing / corrupt stencil JSON is still a hard import failure before any
        // mutation, and the parsed StencilResource objects are reused by the
        // install loop below so each stencil detail is deserialized only once.
        // Other resource types stay in the per-resource try/catch so a
        // missing/corrupt detail surfaces as a single failed install rather than
        // aborting the whole import — but a wire-version gate failure
        // (CatalogSchemaException) is rethrown there to reject the whole import,
        // matching the manifest gate. Stencil-version conflicts remain the only
        // deliberate group-decision (FAIL vs RENUMBER).
        val parsedStencilsBySlug: Map<String, StencilResource> = ordered
            .filter { it.type == "stencil" }
            .associate { entry ->
                val detailBytes = entries[entry.detailUrl.removePrefix("./")]
                    ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                val parsed = schemaMigrator.migrateAndBindResourceDetail(entry.type, detailBytes, catalogCtx).resource
                val stencil = parsed as? StencilResource
                    ?: throw IllegalArgumentException(
                        "Resource at ${entry.detailUrl} declared type 'stencil' but parsed as ${parsed::class.simpleName}",
                    )
                entry.key to stencil
            }
        // Install resources in dependency order
        val results = ordered.map { entry ->
            try {
                val resource: CatalogResource = if (entry.type == "stencil") {
                    parsedStencilsBySlug[entry.key]
                        ?: error("Pre-scan produced no stencil for slug '${entry.key}' (manifest/pre-scan out of sync)")
                } else {
                    val detailPath = entry.detailUrl.removePrefix("./")
                    val detailBytes = entries[detailPath]
                        ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                    schemaMigrator.migrateAndBindResourceDetail(entry.type, detailBytes, catalogCtx).resource
                }

                val status = installResource(
                    tenantKey = command.tenantKey,
                    tenantId = tenantId,
                    catalogKey = catalogKey,
                    resource = resource,
                    version = manifest.release.version,
                    entries = entries,
                )
                InstallResult(type = entry.type, slug = entry.key, status = status)
            } catch (e: CatalogSchemaException) {
                // A wire-version gate failure (too new/old/unknown) rejects the
                // whole import and surfaces the dedicated operator remediation
                // (UI fragment / RFC 9457 problem) — never downgraded to a single
                // FAILED resource by the generic catch below.
                throw e
            } catch (e: Exception) {
                logger.error("Failed to import {} '{}': {}", entry.type, entry.key, e.message, e)
                InstallResult(type = entry.type, slug = entry.key, status = InstallStatus.FAILED, errorMessage = e.message)
            }
        }

        val anyFailed = results.any { it.status == InstallStatus.FAILED }
        val catalogMetadataJson = objectMapper.writeValueAsString(CatalogMetadata.from(manifest.catalog))

        if (command.catalogType == CatalogType.SUBSCRIBED) {
            // A ZIP import of a SUBSCRIBED catalog IS its upgrade — same
            // contract as a URL `UpgradeCatalog`, the ZIP just being the
            // transport instead of a source URL.
            if (anyFailed) {
                // Abort: do NOT prune stale or advance installed_* — the
                // catalog stays on its previous release and a re-import
                // retries (never a silent half-upgrade).
                val failed = results.filter { it.status == InstallStatus.FAILED }
                logger.error(
                    "SUBSCRIBED ZIP import of '{}' ABORTED — {} resource(s) failed; not pruning stale, not advancing installed version: {}",
                    catalogKey.value,
                    failed.size,
                    failed.joinToString { "${it.type}/${it.slug}: ${it.errorMessage}" },
                )
                return@runAsImport ImportCatalogZipResult(catalogKey, manifest.catalog.name, results, aborted = true)
            }
            analyzer.removeStale(command.tenantKey, catalogKey, staleBefore)
            updateSubscribedInstalledState(
                command.tenantKey,
                catalogKey,
                manifest.release.version,
                manifest.release.fingerprint,
                objectMapper.writeValueAsString(subscribedPerResourceFingerprints(manifest, entries)),
                manifest.catalog.name,
                manifest.catalog.description,
                catalogMetadataJson,
            )
            if (!InstalledReleaseRecording.isDeferred) {
                RecordInstalledRelease(command.tenantKey, catalogKey).execute()
            }
        } else if (!anyFailed) {
            updateAuthoredMetadata(
                command.tenantKey,
                catalogKey,
                manifest.catalog.name,
                manifest.catalog.description,
                catalogMetadataJson,
            )
        }

        if (command.catalogType == CatalogType.AUTHORED &&
            existingCatalog == null &&
            manifest.release.fingerprint != null &&
            SemVer.parseOrNull(manifest.release.version) != null &&
            !anyFailed
        ) {
            // AUTHORED: adopt the published version as the *initial* release
            // only when this import created the catalog. A release is an
            // authorship act — importing into an existing AUTHORED catalog is
            // an edit (drift/"unreleased changes" is the correct signal and
            // the owner releases deliberately); `-dev`/legacy versions are
            // nothing real to adopt. Skipped if any resource failed.
            try {
                ReleaseCatalogVersion(
                    tenantKey = command.tenantKey,
                    catalogKey = catalogKey,
                    version = manifest.release.version,
                    notes = "Initial release imported from catalog ZIP (publisher: ${manifest.publisher.name})",
                    publication = ReleasePublication.SUPPRESS,
                ).execute()
                logger.info(
                    "Catalog '{}' created from ZIP — adopted imported release {} as the initial release",
                    catalogKey.value,
                    manifest.release.version,
                )
            } catch (e: CatalogReleaseVersionException) {
                // Defensive only — a brand-new catalog has no prior releases.
                logger.info(
                    "Did not adopt imported release {} for new catalog '{}': {}",
                    manifest.release.version,
                    catalogKey.value,
                    e.message,
                )
            }
        } else if (command.catalogType == CatalogType.AUTHORED &&
            existingCatalog != null &&
            command.authoredMode == AuthoredImportMode.REPLACE
        ) {
            // REPLACE an existing AUTHORED catalog: make it exactly the ZIP by
            // pruning local-only resources (already conflict-checked above).
            // Release state is deliberately untouched — this is an edit; the
            // working copy drifts and the owner re-releases via the Release
            // action. On a failed install we DON'T prune (avoid a half-replace).
            if (anyFailed) {
                logger.warn(
                    "AUTHORED REPLACE of '{}' — {} resource(s) failed; NOT pruning local-only resources (avoiding a half-replace)",
                    catalogKey.value,
                    results.count { it.status == InstallStatus.FAILED },
                )
            } else {
                val removed = analyzer.removeStale(command.tenantKey, catalogKey, staleBefore)
                logger.info(
                    "AUTHORED REPLACE of '{}' — {} local-only resource(s) pruned; release state unchanged",
                    catalogKey.value,
                    removed.size,
                )
            }
        }

        // Record the wholesale content-set point — reached only on non-aborted
        // paths (the SUBSCRIBED abort returns early above). NOW() here is after
        // the resource-install loop, so it is ≥ every imported resource's
        // updated_at: a no-op re-import advances this in lockstep and does NOT
        // register as AUTHORED drift; only a later edit does. With released_at
        // it is the GREATEST(released_at, imported_at) drift baseline.
        touchImportedAt(command.tenantKey, catalogKey)

        ImportCatalogZipResult(
            catalogKey = catalogKey,
            catalogName = manifest.catalog.name,
            results = results,
        )
    }

    private fun touchImportedAt(tenantKey: TenantKey, catalogKey: CatalogKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE catalogs SET imported_at = NOW() WHERE tenant_key = :t AND catalog_key = :c")
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .execute()
        }
    }

    /** Inserts an empty SUBSCRIBED catalog row (no source URL — ZIP-sourced). */
    private fun insertSubscribedCatalog(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        name: String,
        description: String?,
        sourceUrl: String?,
        registry: String,
        namespace: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, description, type, source_url, created_at, updated_at)
                VALUES (:c, :t, :registry, :namespace, :name, :description, 'SUBSCRIBED', :sourceUrl, NOW(), NOW())
                """,
            )
                .bind("c", catalogKey)
                .bind("registry", registry)
                .bind("namespace", namespace)
                .bind("t", tenantKey)
                .bind("name", name)
                .bind("description", description)
                .bind("sourceUrl", sourceUrl)
                .execute()
        }
    }

    /**
     * Advances the SUBSCRIBED install pointer from the imported ZIP — the same
     * `installed_*` state a URL [RegisterCatalog]/[UpgradeCatalog] records,
     * so the upgrade preview / drift detection work identically. Run last,
     * only on a fully successful import.
     */
    private fun updateSubscribedInstalledState(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        fingerprint: String?,
        resourceFingerprintsJson: String,
        name: String,
        description: String?,
        catalogMetadataJson: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, installed_fingerprint = :fingerprint,
                    installed_resource_fingerprints = :resourceFingerprints::jsonb,
                    name = :name, description = :description, catalog_metadata = :catalogMetadata::jsonb,
                    installed_at = NOW(), content_updated_at = NOW(), updated_at = NOW()
                WHERE tenant_key = :t AND catalog_key = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("resourceFingerprints", resourceFingerprintsJson)
                .bind("name", name)
                .bind("description", description)
                .bind("catalogMetadata", catalogMetadataJson)
                .execute()
        }
    }

    private fun updateAuthoredMetadata(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        name: String,
        description: String?,
        catalogMetadataJson: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET name = :name, description = :description, catalog_metadata = :catalogMetadata::jsonb,
                    content_updated_at = NOW(), updated_at = NOW()
                WHERE tenant_key = :t AND catalog_key = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("name", name)
                .bind("description", description)
                .bind("catalogMetadata", catalogMetadataJson)
                .execute()
        }
    }

    /**
     * Per-resource source-side digests from the ZIP bytes — the SUBSCRIBED
     * upgrade-diff baseline, computed with the **exact same** canonicalization
     * as a URL source ([CatalogCanonicalizer.perResourceFingerprintsFromSerializedDetails]),
     * keyed `"$type/$slug"`, asset bytes folded in. Equals what the publisher
     * stamped (deterministic round-trip).
     */
    private fun subscribedPerResourceFingerprints(
        manifest: CatalogManifest,
        entries: Map<String, ByteArray>,
    ): Map<String, String> {
        val detailBytesByKey = manifest.resources.mapNotNull { entry ->
            entries[entry.detailUrl.removePrefix("./")]?.let { "${entry.type}/${entry.key}" to it }
        }.toMap()
        return canonicalizer.perResourceFingerprintsFromSerializedDetails(detailBytesByKey) { contentUrl ->
            entries[contentUrl.removePrefix("./")]
        }
    }

    private fun installResource(
        tenantKey: TenantKey,
        tenantId: TenantId,
        catalogKey: CatalogKey,
        resource: CatalogResource,
        version: String,
        entries: Map<String, ByteArray>,
    ): InstallStatus = when (resource) {
        is TemplateResource -> {
            val input = ImportTemplateInput(
                slug = resource.key,
                name = resource.name,
                version = version,
                themeId = resource.themeKey,
                themeCatalogKey = if (resource.themeKey != null) resource.themeCatalogKey ?: catalogKey.value else null,
                pdfaEnabled = resource.pdfaEnabled,
                dataModel = protocolMapper.toObjectNode(resource.dataModel),
                dataExamples = resource.dataExamples?.map {
                    DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = protocolMapper.toObjectNode(it.data)!!, key = it.key)
                } ?: emptyList(),
                templateModel = resource.templateModel,
                variants = resource.variants.map { variant ->
                    ImportVariantInput(
                        id = variant.key,
                        title = variant.title,
                        attributes = variant.attributes ?: emptyMap(),
                        templateModel = variant.templateModel,
                        isDefault = variant.isDefault,
                    )
                },
            )
            val results = ImportTemplates(
                tenantId = tenantId,
                catalogKey = catalogKey,
                templates = listOf(input),
            ).execute()
            val result = results.first()
            if (result.status == ImportStatus.FAILED) {
                throw RuntimeException("Import failed for '${resource.key}': ${result.errorMessage}")
            }
            if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }

        is ThemeResource -> ImportTheme(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            name = resource.name,
            description = resource.description,
            documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
            pageSettings = resource.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
            spacingUnit = resource.spacingUnit,
        ).execute()

        is StencilResource -> ImportStencil(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            name = resource.name,
            description = resource.description,
            tags = resource.tags,
            content = resource.content,
            parameterSchema = resource.parameterSchema,
            readsTemplateData = resource.readsTemplateData,
        ).execute()

        is AttributeResource -> {
            val bindingCatalog = resource.codeListBinding?.let {
                it.catalogKey?.let(CatalogKey::of) ?: catalogKey
            }
            val bindingSlug = resource.codeListBinding?.key?.let(CodeListKey::of)
            ImportAttribute(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = resource.key,
                displayName = resource.name,
                allowedValues = resource.allowedValues,
                codeListCatalogKey = bindingCatalog,
                codeListSlug = bindingSlug,
            ).execute()
        }

        is app.epistola.catalog.protocol.CodeListResource -> ImportCodeList(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            displayName = resource.name,
            description = resource.description,
            entries = resource.entries.map { wire ->
                app.epistola.suite.attributes.codelists.model.CodeListEntry(
                    code = wire.code,
                    label = wire.label,
                    sortOrder = wire.sortOrder,
                    hidden = wire.hidden,
                )
            },
        ).execute()

        is FontResource -> app.epistola.suite.fonts.commands.ImportFont(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.key,
            name = resource.name,
            kind = resource.kind,
            // Every variant's binary rode the ZIP as an `AssetResource`
            // installed in this same catalog (asset slug = asset UUID).
            // System (CLASSPATH) fonts are never exported.
            variants = resource.variants.map { entry ->
                val faceBytes = entries[entry.contentPath()]
                    ?: throw IllegalArgumentException("Missing font face content: ${entry.contentPath()}")
                val faceKey = materialiseFontFace(tenantId, catalogKey, resource.key, entry, faceBytes)
                app.epistola.suite.fonts.commands.ImportFontVariant(
                    weight = entry.weight,
                    italic = entry.italic,
                    source = app.epistola.suite.fonts.model.FontVariantSource.ASSET,
                    assetKey = faceKey,
                )
            },
        ).execute()

        is ImageResource -> {
            // Resolve binary content from ZIP entries
            val contentPath = resource.contentPath()
            val contentBytes = entries[contentPath]
                ?: throw IllegalArgumentException("Missing image content: ${resource.contentPath()}")
            val mediaType = AssetMediaType.fromMimeType(resource.mediaType)
            // A published catalog may name its images the way a person would -- `municipality-mark`
            // -- and this accepts them. It used to parse the slug as a UUID, because the REST
            // surface could not represent anything else; `/images` addresses an image by `slug`,
            // a plain string, and the asset operations that forced the UUID are gone.
            val assetId = AssetKey.of(resource.key)
            ImportAsset(
                tenantId = tenantId,
                catalogKey = catalogKey,
                id = assetId,
                name = resource.name,
                mediaType = mediaType,
                content = contentBytes,
                width = resource.width,
                height = resource.height,
            ).execute()
        }
    }

    /**
     * Checks all dependencies in a single query per resource type.
     * Returns the list of dependencies that do NOT exist in the target system.
     */
    private fun findMissingDependencies(
        tenantKey: TenantKey,
        deps: List<app.epistola.catalog.protocol.DependencyRef>,
    ): List<app.epistola.catalog.protocol.DependencyRef> {
        if (deps.isEmpty()) return emptyList()

        val found = mutableSetOf<String>() // unique key per dependency

        jdbi.withHandle<Unit, Exception> { handle ->
            // Catalog-scoped: themes
            val themeDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Theme>()
            if (themeDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, th.id FROM themes th JOIN catalogs rc ON rc.tenant_key = th.tenant_key AND rc.catalog_id = th.catalog_id WHERE th.tenant_key = :tenantKey AND th.id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", themeDeps.map { it.key })
                    .map { rs, _ -> "theme:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: stencils
            val stencilDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Stencil>()
            if (stencilDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, st.id FROM stencils st JOIN catalogs rc ON rc.tenant_key = st.tenant_key AND rc.catalog_id = st.catalog_id WHERE st.tenant_key = :tenantKey AND st.id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", stencilDeps.map { it.key })
                    .map { rs, _ -> "stencil:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: images. This probe matched tenant-wide until wire v7, which was
            // only ever safe because an image's slug was a generated UUID -- two catalogs can both
            // hold a `logo`, and a match in the wrong one reported a dependency as satisfied that
            // was not. The dependency names its catalog now, so the probe can too.
            val imageDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Image>()
            if (imageDeps.isNotEmpty()) {
                handle.createQuery(
                    "SELECT rc.catalog_key, a.id::text AS id FROM assets a JOIN catalogs rc ON rc.tenant_key = a.tenant_key AND rc.catalog_id = a.catalog_id WHERE a.tenant_key = :tenantKey AND a.id::text IN (<slugs>)",
                )
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", imageDeps.map { it.key })
                    .map { rs, _ -> "image:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: code lists. Matches the same `catalog_key + slug`
            // shape as themes/stencils so the look-up cost is one PK probe.
            val codeListDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.CodeList>()
            if (codeListDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, cl.slug FROM code_lists cl JOIN catalogs rc ON rc.tenant_key = cl.tenant_key AND rc.catalog_id = cl.catalog_id WHERE cl.tenant_key = :tenantKey AND cl.slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", codeListDeps.map { it.key })
                    .map { rs, _ -> "codeList:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: fonts. Same `catalog_key + slug` PK-probe shape
            // as themes/stencils/code lists.
            val fontDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Font>()
            if (fontDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, fo.slug FROM fonts fo JOIN catalogs rc ON rc.tenant_key = fo.tenant_key AND rc.catalog_id = fo.catalog_id WHERE fo.tenant_key = :tenantKey AND fo.slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", fontDeps.map { it.key })
                    .map { rs, _ -> "font:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }
        }

        return deps.filter { dep ->
            when (dep) {
                is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Image -> "image:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.CodeList -> "codeList:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Font -> "font:${dep.catalogKey}:${dep.key}" !in found
            }
        }
    }
}
