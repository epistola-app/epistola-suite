// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationOptions
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
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.ready.ReadyMarks
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
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

/**
 * Installs the release a catalog archive carries (contract `importCatalog`, ADR 0003): at its number,
 * as an immutable release of the catalog the archive names, beside the releases of it the tenant has.
 * Nothing is merged and no draft is created or changed.
 *
 * - The same number with the same fingerprint is the release the tenant has: nothing changes.
 * - The same number with another fingerprint is refused ([CatalogImportConflictException]).
 * - A new catalog is created with [catalogType], else [newCatalogType] (the web UI, which does not ask:
 *   ADR 0032), holding that release. A catalog the tenant has keeps its type: naming another is refused.
 * - The releases it pins must be present, with the fingerprints they were pinned with
 *   ([CatalogImportDependenciesMissingException]).
 *
 * A SUBSCRIBED catalog also mirrors the release it installs, when it is above the one installed, as
 * its browsable content. An AUTHORED one installs it through a short-lived draft holding the
 * archive's content, released under the archive's number and fingerprint.
 *
 * Internally the same archive reader fills a draft ([intoDraft], copying a release into a draft) or
 * restores a tenant snapshot ([restore]); those keep the per-resource merge they always had.
 */
data class ImportCatalogZip(
    override val tenantKey: TenantKey,
    val zipBytes: ByteArray,
    /** The type of the catalog the import creates; null for a catalog the tenant has. */
    val catalogType: CatalogType? = null,
    val newCatalogType: CatalogType? = null,
    /** How a [restore] treats local-only resources of an authored catalog. Internal. */
    internal val authoredMode: AuthoredImportMode = AuthoredImportMode.MERGE,
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
     * Set when the operator has confirmed updating an **AUTHORED** ZIP whose schema version is below
     * current but migratable. Without it the handler raises [CatalogMigrationConfirmationRequiredException]
     * so the UI can prompt. The REST import sets it `true` (non-interactive).
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
    /**
     * Fills a new draft with a release of the catalog itself (`CreateCatalogVersion` from a released
     * version), into the version that command targets: only the content, its dependency
     * declarations and ready marks are written. The catalog's metadata and its import time stay, since
     * the archive is the catalog's own past, not a new arrival. Internal so no API can set it.
     */
    internal val intoDraft: Boolean = false,
    /**
     * A tenant snapshot's catalog, restored as it was: an authored one fills its next version, as
     * imports did before contract 2.0. Internal so no API can set it.
     */
    internal val restore: Boolean = false,
    /**
     * With [intoDraft], a merge (ADR 0032): only these resources (`type/key`) are written, each
     * marked ready as released content, and the draft's dependency declarations and theme settings
     * stay as they are. Null writes the whole archive. Internal so no API can set it.
     */
    internal val mergeResources: Set<String>? = null,
) : Command<ImportCatalogZipResult>,
    RequiresPermission {
    // Installing a release is managing catalogs (contract `importCatalog`); copying a release into a
    // draft, which starting a draft does, is editing it.
    override val permission get() = if (intoDraft) Permission.TEMPLATE_EDIT else Permission.CATALOG_MANAGE
}

/** The import conflicts with what the tenant has: another type, or the release under other content. */
class CatalogImportConflictException(message: String) : RuntimeException(message)

/** The archive's release pins releases the tenant does not have ([missing]), and the archive does not carry them. */
class CatalogImportDependenciesMissingException(
    val catalogKey: CatalogKey,
    val version: String,
    val missing: List<app.epistola.suite.environments.MissingReleaseDependency>,
) : RuntimeException("${catalogKey.value} $version renders with releases that cannot be had: " + missing.joinToString())

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
    /**
     * For a fat export ([CatalogBundle]): the catalogs it uses that the import installed, as
     * `catalogKey vVERSION`, in the order it installed them, and those this installation had already.
     */
    val installedDependencies: List<String> = emptyList(),
    val presentDependencies: List<String> = emptyList(),
    /** The release the archive carries, which the tenant now has. */
    val releaseVersion: String? = null,
    /** False when the tenant had that release already, with the same fingerprint, and nothing changed. */
    val newlyInstalled: Boolean = true,
    /** The archive's content as its publisher released it, for the release an install records. */
    val published: app.epistola.suite.catalog.CatalogContent? = null,
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
    private val dependencyStore: CatalogDependencyStore,
    private val readyMarks: ReadyMarks,
    private val drafts: app.epistola.suite.catalog.versions.CatalogDrafts,
    private val targeting: app.epistola.suite.catalog.versions.CatalogVersionTargeting,
    private val rows: CatalogImportRows,
    private val nextVersionChanges: app.epistola.suite.catalog.versions.NextVersionChanges,
) : CommandHandler<ImportCatalogZip, ImportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    override fun handle(command: ImportCatalogZip): ImportCatalogZipResult {
        if (command.intoDraft || command.restore) return importArchive(command, requireNotNull(command.catalogType) { "an internal import names its type" })
        return install(command)
    }

    /** What [handle] does for an archive: installs its release (see [ImportCatalogZip]). */
    private fun install(command: ImportCatalogZip): ImportCatalogZipResult {
        val parsed = parse(command, command.catalogType)
        val manifest = parsed.manifest
        val catalogKey = CatalogKey.of(manifest.catalog.key)
        val registry = manifest.catalog.registry ?: identity.defaultRegistry
        val namespace = manifest.catalog.namespace ?: CatalogIdentityProperties.defaultNamespace(command.tenantKey)
        val existing = GetCatalog(command.tenantKey, catalogKey).query()
        if (existing != null && (existing.registry != registry || existing.namespace != namespace)) {
            throw CatalogImportConflictException(
                "This tenant has a catalog '${catalogKey.value}' in ${existing.registry}/${existing.namespace}; the archive names $registry/$namespace/${catalogKey.value}, another catalog.",
            )
        }
        if (existing != null && command.catalogType != null && command.catalogType != existing.type) {
            throw CatalogImportConflictException("Catalog '${catalogKey.value}' is ${existing.type}; an import never changes a catalog's type.")
        }
        val type = existing?.type ?: command.catalogType ?: command.newCatalogType
            ?: throw ValidationException("catalogType", "Name the type of the catalog this import creates: AUTHORED or SUBSCRIBED", ValidationCode.GENERIC)
        val version = manifest.release.version
        val published = manifest.release.fingerprint

        // The releases it carries, before it: each one this tenant lacks is installed, in a
        // subscribed catalog when the tenant does not have that catalog; one it has is left alone.
        val installedDependencies = mutableListOf<String>()
        val presentDependencies = mutableListOf<String>()
        for ((release, bytes) in parsed.carried) {
            val label = "${release.catalogKey} v${release.releaseVersion}"
            val dependencyCatalog = GetCatalog(command.tenantKey, CatalogKey.of(release.catalogKey)).query()
            val dependency = install(
                ImportCatalogZip(
                    tenantKey = command.tenantKey,
                    zipBytes = bytes,
                    catalogType = if (dependencyCatalog == null) CatalogType.SUBSCRIBED else null,
                    confirmMigration = true,
                ),
            )
            if (dependency.newlyInstalled) installedDependencies += label else presentDependencies += label
        }

        fun result(newly: Boolean) = ImportCatalogZipResult(
            catalogKey = catalogKey,
            catalogName = manifest.catalog.name,
            results = manifest.resources.map { InstallResult(it.type, it.key, if (newly) InstallStatus.INSTALLED else InstallStatus.SKIPPED) },
            releaseVersion = version,
            newlyInstalled = newly,
            installedDependencies = installedDependencies,
            presentDependencies = presentDependencies,
        )

        val present = existing?.let { ListCatalogReleases(command.tenantKey, catalogKey).query().firstOrNull { it.version == version } }
        if (present != null) {
            if (published == null || present.fingerprint == published) return result(newly = false)
            throw CatalogImportConflictException(
                "This tenant has ${catalogKey.value} $version with other content (fingerprint ${present.fingerprint}, the archive's $published); one number names one release.",
            )
        }

        val missing = jdbi.withHandle<List<app.epistola.suite.environments.MissingReleaseDependency>, Exception> { handle ->
            manifest.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of)
                .filter { it.pinned && !dependencyStore.pinHolds(handle, command.tenantKey, it) }
                .map { app.epistola.suite.environments.MissingReleaseDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion!!, app.epistola.suite.environments.MissingDependencyReason.UNAVAILABLE) }
        }
        if (missing.isNotEmpty()) throw CatalogImportDependenciesMissingException(catalogKey, version, missing)

        when (type) {
            CatalogType.SUBSCRIBED -> {
                val installed = existing?.installedReleaseVersion?.let(SemVer::parseOrNull)
                val incoming = SemVer.parseOrNull(version)
                // Installed beside the others (contract `installCatalogRelease`): a release below the
                // installed one, or any while the next version has changes of its own (ADR 0032), is
                // recorded from the archive as published; the next version stays, behind it.
                val older = installed != null && incoming != null && incoming < installed
                if (older || (existing != null && nextVersionChanges.hasChanges(command.tenantKey, catalogKey))) {
                    val bound = parsed.manifest.resources.associate { entry ->
                        val detail = parsed.entries[entry.detailUrl.removePrefix("./")]
                            ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                        "${entry.type}/${entry.key}" to schemaMigrator.migrateAndBindResourceDetail(entry.type, detail, parsed.catalogCtx).resource
                    }
                    RecordInstalledRelease(
                        command.tenantKey,
                        catalogKey,
                        published = publishedContent(manifest, bound, parsed.entries),
                        release = RecordedRelease(version, published),
                    ).execute()
                    return result(newly = true)
                }
                val imported = importArchive(command, CatalogType.SUBSCRIBED)
                // Nothing was advanced: the catalog stays on the release it had, and the caller
                // decides what to do with what the failed install left (an exchange install
                // removes a catalog it created).
                if (imported.aborted) return imported.copy(releaseVersion = version, newlyInstalled = false, installedDependencies = installedDependencies, presentDependencies = presentDependencies)
            }

            CatalogType.AUTHORED -> installAuthored(command, manifest, catalogKey, existing == null)
        }
        return result(newly = true)
    }

    /**
     * Installs the archive's release in an authored catalog: its content into a draft, released at
     * the archive's number with the archive's fingerprint, which the draft then becomes.
     *
     * A catalog the import creates gets its next version, as every authored catalog of this suite
     * has one: the archive fills it, and after the release it continues on it, as a copy of what was
     * installed. That is where references from other catalogs resolve, and where the author edits.
     * (The contract has a new catalog hold no draft; the next version is this suite's convention.)
     * An existing catalog gets the release through a draft of its own, which then is gone, so its
     * drafts are untouched.
     */
    private fun installAuthored(command: ImportCatalogZip, manifest: app.epistola.catalog.protocol.CatalogManifest, catalogKey: CatalogKey, creates: Boolean) {
        if (creates) {
            CreateCatalog(
                tenantKey = command.tenantKey,
                id = catalogKey,
                name = manifest.catalog.name,
                description = manifest.catalog.description,
                namespace = manifest.catalog.namespace,
                registry = manifest.catalog.registry,
            ).execute()
            rows.updateAuthoredMetadata(
                command.tenantKey,
                catalogKey,
                manifest.catalog.name,
                manifest.catalog.description,
                objectMapper.writeValueAsString(CatalogMetadata.from(manifest.catalog)),
            )
        }
        val version = manifest.release.version
        val holder = jdbi.withHandle<app.epistola.suite.catalog.versions.CatalogDraft, Exception> { handle ->
            if (creates) {
                requireNotNull(drafts.next(handle, command.tenantKey, catalogKey)) { "a new authored catalog has its next version" }
            } else {
                val id = drafts.open(handle, command.tenantKey, catalogKey, base = null, name = "Installing $version (${app.epistola.suite.common.UUIDv7.generate().toString().takeLast(8)})")
                drafts.drafts(handle, command.tenantKey, catalogKey).first { it.versionId == id }
            }
        }
        val filled = targeting.within(holder.versionId) { importArchive(command.copy(intoDraft = true), CatalogType.AUTHORED) }
        val failed = filled.results.filter { it.status == InstallStatus.FAILED }
        check(failed.isEmpty()) { "Installing ${catalogKey.value} $version failed for " + failed.joinToString { "${it.type} '${it.slug}': ${it.errorMessage}" } }
        // The release takes the archive's theme settings (contract ADR 0007) from the draft it is cut from.
        targeting.within(holder.versionId) { rows.updateThemeSettings(command.tenantKey, catalogKey, manifest.catalog, objectMapper) }
        ReleaseCatalogVersion(
            tenantKey = command.tenantKey,
            catalogKey = catalogKey,
            version = version,
            notes = "Installed from a catalog archive (publisher: ${manifest.publisher.name})",
            publication = ReleasePublication.SUPPRESS,
            catalogVersion = app.epistola.suite.catalog.versions.CatalogVersionRef(command.tenantKey, catalogKey, holder.ref),
            imported = ImportedRelease(manifest.release.fingerprint, manifest.release.releasedAt?.let { runCatching { java.time.OffsetDateTime.parse(it) }.getOrNull() }, filled.published),
        ).execute()
        rows.touchImportedAt(command.tenantKey, catalogKey)
    }

    private data class Parsed(
        val entries: Map<String, ByteArray>,
        val manifest: app.epistola.catalog.protocol.CatalogManifest,
        val catalogCtx: app.epistola.suite.catalog.migrations.CatalogMigrationContext,
        /** The releases the archive carries for its pins, each as an archive of its own, in install order. */
        val carried: List<Pair<app.epistola.catalog.archive.EmbeddedCatalogRelease, ByteArray>> = emptyList(),
    )

    private fun parse(command: ImportCatalogZip, catalogType: CatalogType?): Parsed {
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
        // An archive from before wire v8 names no registry or namespace: it lands in the importing
        // tenant's namespace on the default registry (contract ADR 0003). The reader migrates it, so
        // it is told here, not only the manifest binding below.
        val migration = CatalogMigrationOptions(
            namespace = CatalogIdentityProperties.defaultNamespace(command.tenantKey),
            registry = identity.defaultRegistry,
        )
        val read = CatalogArchiveReader.read(
            ByteArrayInputStream(command.zipBytes),
            CatalogArchivePolicy(
                maxCompressedBytes = maxZipSize,
                maxExpandedBytes = maxDecompressedSize,
            ),
            migration,
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
        var carried: List<Pair<app.epistola.catalog.archive.EmbeddedCatalogRelease, ByteArray>> = emptyList()
        val entries = archive.use { safeArchive ->
            val validation = CatalogValidator.validate(
                safeArchive,
                CatalogValidationPolicy(verifyFingerprint = true, migration = migration),
            )
            val validationFindings = read.findings.map {
                CatalogImportValidationFinding(it.code, ValidationSeverity.ERROR, it.path, it.message)
            } + validation.findings.map {
                CatalogImportValidationFinding(it.code, it.severity, it.path, it.message)
            }
            if (read.findings.isNotEmpty() || !validation.valid) {
                throw CatalogImportValidationException(validationFindings)
            }
            carried = safeArchive.dependencies.map { release ->
                release to java.io.ByteArrayOutputStream().also {
                    app.epistola.catalog.archive.CatalogArchiveWriter.write(
                        release.archive,
                        it,
                        CatalogArchivePolicy(maxCompressedBytes = maxZipSize, maxExpandedBytes = maxDecompressedSize),
                    )
                }.toByteArray()
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
        val migratedManifest = schemaMigrator.migrateAndBindManifest(manifestBytes, migration.namespace)
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
                catalogType = catalogType ?: CatalogType.AUTHORED,
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
        return Parsed(entries, manifest, catalogCtx, carried)
    }

    private fun importArchive(command: ImportCatalogZip, catalogType: CatalogType): ImportCatalogZipResult = CatalogImportContext.runAsImport {
        val (entries, manifest, catalogCtx) = parse(command, catalogType)
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
        // Filling a draft (a release copied in, or merged) writes content whatever the type.
        if (existingCatalog != null && existingCatalog.type != catalogType && !command.intoDraft) {
            throw IllegalArgumentException(
                "Catalog '${catalogKey.value}' already exists as ${existingCatalog.type} — " +
                    "cannot import it as $catalogType.",
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
            val missing = rows.findMissingDependencies(command.tenantKey, dependencies)
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
        val prunesStale = catalogType == CatalogType.SUBSCRIBED ||
            (
                catalogType == CatalogType.AUTHORED &&
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
            when (catalogType) {
                // A catalog keeps the name its archive gives it (contract ADR 0003).
                CatalogType.AUTHORED -> CreateCatalog(
                    tenantKey = command.tenantKey,
                    id = catalogKey,
                    name = manifest.catalog.name,
                    description = manifest.catalog.description,
                    namespace = manifest.catalog.namespace,
                    registry = manifest.catalog.registry,
                ).execute()

                CatalogType.SUBSCRIBED -> rows.insertSubscribedCatalog(
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

        val ordered = manifest.resources
            .filter { command.mergeResources == null || "${it.type}/${it.key}" in command.mergeResources }
            .sortedBy { RESOURCE_INSTALL_ORDER[it.type] ?: 99 }

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
        // Install resources in dependency order. What each binds to is kept as well: a subscribed
        // catalog records its release from the archive as published, not from the rows it mirrors.
        val bound = linkedMapOf<String, CatalogResource>()
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
                bound["${entry.type}/${entry.key}"] = resource

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
        val declared = manifest.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of)

        if (catalogType == CatalogType.SUBSCRIBED) {
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
            rows.updateSubscribedInstalledState(
                command.tenantKey,
                catalogKey,
                manifest.release.version,
                manifest.release.fingerprint,
                objectMapper.writeValueAsString(subscribedPerResourceFingerprints(manifest, entries)),
                manifest.catalog.name,
                manifest.catalog.description,
                catalogMetadataJson,
            )
            jdbi.useHandle<Exception> { dependencyStore.replace(it, command.tenantKey, catalogKey, declared) }
            rows.updateThemeSettings(command.tenantKey, catalogKey, manifest.catalog, objectMapper)
            if (!InstalledReleaseRecording.isDeferred) {
                RecordInstalledRelease(command.tenantKey, catalogKey, published = publishedContent(manifest, bound, entries)).execute()
            }
        } else if (!anyFailed && command.mergeResources != null) {
            // A merge takes resources only; everything else of the draft stays its own.
            jdbi.useHandle<Exception> { handle ->
                for (entry in ordered) {
                    readyMarks.resourceId(handle, command.tenantKey, catalogKey, entry.type, entry.key)
                        ?.let { readyMarks.mark(handle, command.tenantKey, it) }
                }
            }
        } else if (!anyFailed) {
            if (!command.intoDraft) {
                rows.updateAuthoredMetadata(
                    command.tenantKey,
                    catalogKey,
                    manifest.catalog.name,
                    manifest.catalog.description,
                    catalogMetadataJson,
                )
            }
            // The archive's declarations, locked as it was cut. A merge keeps those it does not name.
            jdbi.useHandle<Exception> { handle ->
                val kept = if (existingCatalog != null && command.authoredMode != AuthoredImportMode.REPLACE) {
                    dependencyStore.declarations(handle, command.tenantKey, catalogKey).filter { local -> declared.none { it.identity == local.identity } }
                } else {
                    emptyList()
                }
                dependencyStore.replace(handle, command.tenantKey, catalogKey, declared + kept)
            }
            // A merge into existing work keeps its theme settings; a fresh or replacing import, or one
            // filling a draft (a release copied into it), takes the archive's.
            if (existingCatalog == null || command.authoredMode == AuthoredImportMode.REPLACE || command.intoDraft) {
                rows.updateThemeSettings(command.tenantKey, catalogKey, manifest.catalog, objectMapper)
            }
            jdbi.useHandle<Exception> { handle ->
                // What an archive brings is released content: imported, every resource of it is
                // ready as it is (contract ADR 0002), which adopting its release below relies on.
                for (entry in manifest.resources) {
                    readyMarks.resourceId(handle, command.tenantKey, catalogKey, entry.type, entry.key)
                        ?.let { readyMarks.mark(handle, command.tenantKey, it) }
                }
            }
        }

        if (catalogType == CatalogType.AUTHORED &&
            existingCatalog == null &&
            manifest.release.fingerprint != null &&
            SemVer.parseOrNull(manifest.release.version) != null &&
            !anyFailed &&
            dependencyLocksHold(command.tenantKey, catalogKey, manifest.release.version)
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
        } else if (catalogType == CatalogType.AUTHORED &&
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
        if (!command.intoDraft) rows.touchImportedAt(command.tenantKey, catalogKey)

        ImportCatalogZipResult(
            catalogKey = catalogKey,
            catalogName = manifest.catalog.name,
            results = results,
            published = publishedContent(manifest, bound, entries),
        )
    }

    /**
     * Whether every dependency the archive declares is locked to a release this tenant has as the
     * archive pinned it. Only then is its release adopted: a release is cut with exactly those, and
     * releasing would refuse otherwise. The catalog still imports, as unreleased work.
     */
    private fun dependencyLocksHold(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val unresolved = dependencyStore.declarations(handle, tenantKey, catalogKey).filterNot { dependencyStore.pinHolds(handle, tenantKey, it) }
        if (unresolved.isNotEmpty()) {
            logger.info(
                "Did not adopt imported release {} for new catalog '{}': this tenant lacks the dependency releases it was cut with: {}",
                version,
                catalogKey.value,
                unresolved.joinToString { it.toString() },
            )
        }
        unresolved.isEmpty()
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
}
