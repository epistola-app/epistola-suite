package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AssetResource
import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogImportContext
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
import java.util.zip.ZipInputStream

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
     * What to do when an imported stencil version collides with an
     * already-installed version of the same (slug, version) carrying
     * different content. `FAIL` (default) aborts the whole import with a
     * structured report so the operator can decide. `RENUMBER` is only
     * accepted for **AUTHORED MERGE** imports — the conflicting source
     * stencil version is installed at `MAX(target.version) + 1` and the
     * matching pins in templates from the same ZIP are rewritten to the
     * new number. SUBSCRIBED and AUTHORED REPLACE are mirror-semantic
     * imports — renumber there would silently diverge the mirror from
     * source, so the orchestrator rejects it.
     */
    val onStencilConflict: OnStencilConflict = OnStencilConflict.FAIL,
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

        // Extract ZIP contents into memory with decompression limit
        val entries = mutableMapOf<String, ByteArray>()
        var totalDecompressed = 0L
        ZipInputStream(ByteArrayInputStream(command.zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readAllBytes()
                    totalDecompressed += bytes.size
                    require(totalDecompressed <= maxDecompressedSize) {
                        "Catalog ZIP decompressed content exceeds maximum size of ${sizeLimits.maxDecompressedSize}"
                    }
                    entries[entry.name] = bytes
                }
                entry = zip.nextEntry
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
        val migratedManifest = schemaMigrator.migrateAndBindManifest(manifestBytes)
        val manifest = migratedManifest.manifest
        val catalogCtx = migratedManifest.catalog
        // A ZIP is a one-shot, source-less transport: unlike a subscribed URL we
        // cannot re-fetch a current copy, and we deliberately do not migrate stored
        // content in place. So an outdated-schema ZIP that the migrator could NOT
        // bring to current is rejected outright (the publisher must re-export from a
        // current source) rather than bound as-is under the migrator's transitional
        // leniency. The check is on the *migrated* manifest version: a real chain
        // upgrades it to current (not blocked); only the transitional/un-upgraded
        // case stays sub-current. Covers both the UI and REST import paths.
        if (manifest.schemaVersion < CATALOG_SCHEMA_VERSION) {
            throw CatalogSchemaTooOldException(catalogCtx.sourceVersion, CATALOG_SCHEMA_VERSION)
        }
        val catalogKey = CatalogKey.of(manifest.catalog.slug)

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

        // Renumber-on-stencil-conflict is mirror-incompatible. SUBSCRIBED *is*
        // a mirror by definition (source wins); AUTHORED REPLACE is the explicit
        // "make my catalog exactly this ZIP" mode. Allowing renumber there would
        // silently let target diverge from source. Restrict to AUTHORED MERGE.
        if (command.onStencilConflict == OnStencilConflict.RENUMBER &&
            !(command.catalogType == CatalogType.AUTHORED && command.authoredMode == AuthoredImportMode.MERGE)
        ) {
            throw IllegalArgumentException(
                "onStencilConflict=RENUMBER is only supported for AUTHORED MERGE imports " +
                    "(got catalogType=${command.catalogType}, authoredMode=${command.authoredMode}).",
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
                        is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme '${dep.slug}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil '${dep.slug}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Asset -> "asset '${dep.slug}'"
                        is app.epistola.catalog.protocol.DependencyRef.CodeList -> "code list '${dep.slug}' from catalog '${dep.catalogKey}'"
                        is app.epistola.catalog.protocol.DependencyRef.Font -> "font '${dep.slug}' from catalog '${dep.catalogKey}'"
                    }
                }
                throw IllegalArgumentException(
                    "Catalog '${catalogKey.value}' has unmet dependencies: $details. " +
                        "Import or create these resources first.",
                )
            }
        }

        val tenantId = TenantId(command.tenantKey)
        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.slug })

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
                CatalogType.AUTHORED -> CreateCatalog(
                    tenantKey = command.tenantKey,
                    id = catalogKey,
                    name = manifest.catalog.name,
                    description = manifest.catalog.description,
                ).execute()

                CatalogType.SUBSCRIBED -> insertSubscribedCatalog(
                    command.tenantKey,
                    catalogKey,
                    manifest.catalog.name,
                    manifest.catalog.description,
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
                entry.slug to stencil
            }
        val stencilConflicts = scanStencilVersionConflicts(
            command.tenantKey,
            catalogKey,
            parsedStencilsBySlug.values.toList(),
        )
        if (stencilConflicts.isNotEmpty() && command.onStencilConflict == OnStencilConflict.FAIL) {
            throw StencilVersionImportConflictsException(catalogKey, stencilConflicts)
        }

        // Renumber decisions collected as stencils install — handed to
        // ImportTemplates so template stencil-node pins from the same ZIP can
        // be rewritten in lockstep. Filled in the install loop below.
        val stencilRenumbers: MutableMap<StencilKey, StencilRenumber> = mutableMapOf()

        // Install resources in dependency order
        val results = ordered.map { entry ->
            try {
                val resource: CatalogResource = if (entry.type == "stencil") {
                    parsedStencilsBySlug[entry.slug]
                        ?: error("Pre-scan produced no stencil for slug '${entry.slug}' (manifest/pre-scan out of sync)")
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
                    onStencilConflict = command.onStencilConflict,
                    stencilRenumbers = stencilRenumbers,
                )
                InstallResult(type = entry.type, slug = entry.slug, status = status)
            } catch (e: CatalogSchemaException) {
                // A wire-version gate failure (too new/old/unknown) rejects the
                // whole import and surfaces the dedicated operator remediation
                // (UI fragment / RFC 9457 problem) — never downgraded to a single
                // FAILED resource by the generic catch below.
                throw e
            } catch (e: Exception) {
                logger.error("Failed to import {} '{}': {}", entry.type, entry.slug, e.message, e)
                InstallResult(type = entry.type, slug = entry.slug, status = InstallStatus.FAILED, errorMessage = e.message)
            }
        }

        val anyFailed = results.any { it.status == InstallStatus.FAILED }

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
            )
        } else if (existingCatalog == null &&
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
            handle.createUpdate("UPDATE catalogs SET imported_at = NOW() WHERE tenant_key = :t AND id = :c")
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
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, description, type, created_at, updated_at)
                VALUES (:c, :t, :name, :description, 'SUBSCRIBED', NOW(), NOW())
                """,
            )
                .bind("c", catalogKey)
                .bind("t", tenantKey)
                .bind("name", name)
                .bind("description", description)
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
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, installed_fingerprint = :fingerprint,
                    installed_resource_fingerprints = :resourceFingerprints::jsonb,
                    name = :name, description = :description, updated_at = NOW()
                WHERE tenant_key = :t AND id = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("resourceFingerprints", resourceFingerprintsJson)
                .bind("name", name)
                .bind("description", description)
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
            entries[entry.detailUrl.removePrefix("./")]?.let { "${entry.type}/${entry.slug}" to it }
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
        onStencilConflict: OnStencilConflict,
        stencilRenumbers: MutableMap<StencilKey, StencilRenumber>,
    ): InstallStatus = when (resource) {
        is TemplateResource -> {
            val input = ImportTemplateInput(
                slug = resource.slug,
                name = resource.name,
                version = version,
                themeId = resource.themeId,
                themeCatalogKey = if (resource.themeId != null) resource.themeCatalogKey ?: catalogKey.value else null,
                dataModel = protocolMapper.toObjectNode(resource.dataModel),
                dataExamples = resource.dataExamples?.map {
                    DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = protocolMapper.toObjectNode(it.data)!!)
                } ?: emptyList(),
                templateModel = resource.templateModel,
                variants = resource.variants.map { variant ->
                    ImportVariantInput(
                        id = variant.id,
                        title = variant.title,
                        attributes = variant.attributes ?: emptyMap(),
                        templateModel = variant.templateModel,
                        isDefault = variant.isDefault,
                    )
                },
                publishTo = emptyList(),
            )
            val results = ImportTemplates(
                tenantId = tenantId,
                catalogKey = catalogKey,
                templates = listOf(input),
                stencilRenumbers = stencilRenumbers.toMap(),
            ).execute()
            val result = results.first()
            if (result.status == ImportStatus.FAILED) {
                throw RuntimeException("Import failed for '${resource.slug}': ${result.errorMessage}")
            }
            if (result.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }

        is ThemeResource -> ImportTheme(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
            name = resource.name,
            description = resource.description,
            documentStyles = protocolMapper.mapToDocumentStyles(resource.documentStyles),
            pageSettings = resource.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(resource.blockStylePresets),
            spacingUnit = resource.spacingUnit,
        ).execute()

        is StencilResource -> {
            val result = ImportStencil(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = resource.slug,
                name = resource.name,
                version = resource.version,
                description = resource.description,
                tags = resource.tags ?: emptyList(),
                content = resource.content,
                onConflict = onStencilConflict,
            ).execute()
            if (result.wasRenumbered) {
                stencilRenumbers[StencilKey.of(resource.slug)] = StencilRenumber(
                    sourceVersion = resource.version,
                    assignedVersion = result.assignedVersion,
                )
            }
            result.status
        }

        is AttributeResource -> {
            val bindingCatalog = resource.codeListBinding?.let {
                it.catalogKey?.let(CatalogKey::of) ?: catalogKey
            }
            val bindingSlug = resource.codeListBinding?.slug?.let(CodeListKey::of)
            ImportAttribute(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = resource.slug,
                displayName = resource.name,
                allowedValues = resource.allowedValues ?: emptyList(),
                codeListCatalogKey = bindingCatalog,
                codeListSlug = bindingSlug,
            ).execute()
        }

        is app.epistola.catalog.protocol.CodeListResource -> ImportCodeList(
            tenantId = tenantId,
            catalogKey = catalogKey,
            slug = resource.slug,
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
            slug = resource.slug,
            name = resource.name,
            kind = resource.kind,
            // Every variant's binary rode the ZIP as an `AssetResource`
            // installed in this same catalog (asset slug = asset UUID).
            // System (CLASSPATH) fonts are never exported.
            variants = resource.variants.map { entry ->
                app.epistola.suite.fonts.commands.ImportFontVariant(
                    weight = entry.weight,
                    italic = entry.italic,
                    source = app.epistola.suite.fonts.model.FontVariantSource.ASSET,
                    assetKey = AssetKey.of(java.util.UUID.fromString(entry.assetSlug)),
                )
            },
        ).execute()

        is AssetResource -> {
            // Resolve binary content from ZIP entries
            val contentPath = resource.contentUrl.removePrefix("./")
            val contentBytes = entries[contentPath]
                ?: throw IllegalArgumentException("Missing asset content: ${resource.contentUrl}")
            val mediaType = AssetMediaType.fromMimeType(resource.mediaType)
            // Asset slug is the UUID string
            val assetId = AssetKey.of(java.util.UUID.fromString(resource.slug))
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
                handle.createQuery("SELECT catalog_key, id FROM themes WHERE tenant_key = :tenantKey AND id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", themeDeps.map { it.slug })
                    .map { rs, _ -> "theme:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: stencils
            val stencilDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Stencil>()
            if (stencilDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, id FROM stencils WHERE tenant_key = :tenantKey AND id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", stencilDeps.map { it.slug })
                    .map { rs, _ -> "stencil:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Tenant-global: assets
            val assetDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Asset>()
            if (assetDeps.isNotEmpty()) {
                handle.createQuery("SELECT id::text FROM assets WHERE tenant_key = :tenantKey AND id::text IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", assetDeps.map { it.slug })
                    .mapTo(String::class.java)
                    .list()
                    .forEach { found.add("asset:$it") }
            }

            // Catalog-scoped: code lists. Matches the same `catalog_key + slug`
            // shape as themes/stencils so the look-up cost is one PK probe.
            val codeListDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.CodeList>()
            if (codeListDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, slug FROM code_lists WHERE tenant_key = :tenantKey AND slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", codeListDeps.map { it.slug })
                    .map { rs, _ -> "codeList:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: fonts. Same `catalog_key + slug` PK-probe shape
            // as themes/stencils/code lists.
            val fontDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Font>()
            if (fontDeps.isNotEmpty()) {
                handle.createQuery("SELECT catalog_key, slug FROM fonts WHERE tenant_key = :tenantKey AND slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", fontDeps.map { it.slug })
                    .map { rs, _ -> "font:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }
        }

        return deps.filter { dep ->
            when (dep) {
                is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Asset -> "asset:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.CodeList -> "codeList:${dep.catalogKey}:${dep.slug}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Font -> "font:${dep.catalogKey}:${dep.slug}" !in found
            }
        }
    }

    /**
     * Read-only conflict scan over every stencil in the manifest. For each
     * (slug, version) carried by the ZIP, compares the source content against
     * what target already has at that exact version. Returns one entry per
     * stencil where target has a row with **different content** — same content
     * is idempotent, missing rows are installable. No mutation; safe to run
     * before deciding whether to proceed (FAIL aborts; RENUMBER overlays).
     *
     * All `(slug, version, content)` tuples are sent in a single batched query
     * via a `VALUES` CTE, so the round-trip cost is O(1) instead of O(N).
     */
    private fun scanStencilVersionConflicts(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        stencils: List<StencilResource>,
    ): List<StencilVersionImportConflictsException.StencilImportConflict> {
        if (stencils.isEmpty()) return emptyList()

        val valuesPlaceholders = stencils.indices.joinToString(", ") { idx ->
            "(:slug$idx, :ver$idx, :content$idx)"
        }
        val sql = """
            WITH incoming AS (
                SELECT
                    slug::text   AS slug,
                    version::int AS version,
                    content::jsonb AS content
                FROM (VALUES $valuesPlaceholders) AS t(slug, version, content)
            )
            SELECT i.slug AS slug, i.version AS version
            FROM incoming i
            JOIN stencil_versions sv
              ON sv.tenant_key  = :tenantKey
             AND sv.catalog_key = :catalogKey
             AND sv.stencil_key = i.slug
             AND sv.id          = i.version
            WHERE sv.content <> i.content
        """.trimIndent()

        val conflictKeys: Set<Pair<String, Int>> = jdbi.withHandle<Set<Pair<String, Int>>, Exception> { handle ->
            val query = handle.createQuery(sql)
                .bind("tenantKey", tenantKey)
                .bind("catalogKey", catalogKey)
            stencils.forEachIndexed { idx, resource ->
                query
                    .bind("slug$idx", resource.slug)
                    .bind("ver$idx", resource.version)
                    .bind("content$idx", objectMapper.writeValueAsString(resource.content))
            }
            query.map { rs, _ -> rs.getString("slug") to rs.getInt("version") }
                .list()
                .toSet()
        }

        return stencils
            .filter { (it.slug to it.version) in conflictKeys }
            .map { resource ->
                StencilVersionImportConflictsException.StencilImportConflict(
                    stencilKey = StencilKey.of(resource.slug),
                    stencilName = resource.name,
                    version = resource.version,
                )
            }
    }
}
