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
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogImportSchemaAction
import app.epistola.suite.catalog.CatalogMetadata
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.CatalogUpgradeAnalyzer
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.RESOURCE_INSTALL_ORDER
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.dependencies.DependencyAliases
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
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
 * Receives the release a catalog archive carries (contract `importCatalog`, ADR 0003 and 0032): at its
 * number, as an immutable release of the catalog the archive names, beside the releases of it the
 * tenant has. Every way a release arrives takes this one path (ADR 0032, section 4).
 *
 * - The same number with the same fingerprint is the release the tenant has: nothing changes.
 * - The same number with another fingerprint is refused ([CatalogImportConflictException]).
 * - A catalog the tenant does not have is created as the archive names it, with [sourceUrl] as its
 *   source, holding that release; its next version is on it.
 * - For a catalog the tenant has, a release below its highest, or any while its next version has
 *   changes of its own, is recorded beside the others: the next version stays, behind it, to be
 *   merged. Otherwise the next version moves to it (a fast-forward).
 * - The releases it pins must be present, with the fingerprints they were pinned with
 *   ([CatalogImportDependenciesMissingException]).
 *
 * Internally the same archive reader fills a draft ([intoDraft], copying a release into a draft) or
 * restores a tenant snapshot ([restore]); those keep the per-resource import they always had, into
 * the version the transaction targets. Each resource is written by [CatalogResourceInstaller].
 */
data class ImportCatalogZip(
    override val tenantKey: TenantKey,
    val zipBytes: ByteArray,
    /** How a [restore] treats resources the catalog has and the archive does not. Internal. */
    internal val fillMode: DraftFillMode = DraftFillMode.MERGE,
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
     * Set when the operator has confirmed importing an archive whose schema version is below current
     * but migratable. Without it the handler raises [CatalogMigrationConfirmationRequiredException]
     * so the UI can prompt. Imports nobody is asked about (REST, Exchange, a registry) set it `true`.
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
     * Null for a ZIP somebody uploaded, which leaves the catalog without a source to ask. An existing
     * catalog never has its source rewritten by an import: a restore re-imports content without
     * knowing the source, so a null here must leave an existing source alone rather than clear it.
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
     * A tenant snapshot's catalog, restored as it was: it fills the catalog's next version, as imports
     * did before contract 2.0. Internal so no API can set it.
     */
    internal val restore: Boolean = false,
    /**
     * The registry and namespace the caller names the catalog by (contract `importCatalog`'s
     * `registry` and `namespace`). An archive from before wire v8 names neither, and they are its
     * name, which an import never invents: both are required for it
     * ([CatalogNameRequiredException] without them). An archive at wire v8 names its own; given, they
     * must be what it names, since an import never renames ([CatalogNameMismatchException]).
     */
    val registry: String? = null,
    val namespace: String? = null,
    /**
     * The name an archive from before wire v8 gets when [registry] and [namespace] are not given,
     * for an install whose source names the catalog (an Exchange or registry install, a catalog's
     * source, a snapshot): only such an archive takes it, and a v8 one is not held to it. Internal.
     */
    internal val legacyName: LegacyCatalogName? = null,
    /**
     * A tenant snapshot's release of a catalog it restores: recorded beside the others from the
     * archive, as it says it is, and never put in the next version, which the snapshot's working
     * copy fills. Its pins are not checked: the snapshot restores what was. Internal.
     */
    internal val restoreRelease: Boolean = false,
) : Command<ImportCatalogZipResult>,
    RequiresPermission {
    // Installing a release is managing catalogs (contract `importCatalog`); copying a release into a
    // draft, which starting a draft does, is editing it.
    override val permission get() = if (intoDraft) Permission.TEMPLATE_EDIT else Permission.CATALOG_MANAGE
}

/** The import conflicts with what the tenant has: another catalog under the key, or the release under other content. */
open class CatalogImportConflictException(message: String) : RuntimeException(message)

/**
 * The import names a release the tenant has as another: its number with other content, or its id
 * held by another release or draft. One number and one id name one release (REST
 * `release-number-taken`).
 */
class CatalogReleaseTakenException(message: String) : CatalogImportConflictException(message)

/** The registry and namespace an install's source gives a catalog whose archive is from before wire v8. */
data class LegacyCatalogName(val registry: String, val namespace: String)

/**
 * The archive is from before wire v8 ([sourceVersion]) and names no registry or namespace; the
 * import must be told both, since they are the catalog's name and are never invented.
 */
class CatalogNameRequiredException(val missing: List<String>) : RuntimeException("This archive is from before catalogs named their registry and namespace; name both (${missing.joinToString(" and ")} missing) to import it.")

/** The registry or namespace the import was given is not what the archive names: an import never renames a catalog. */
class CatalogNameMismatchException(val field: String, val given: String, val named: String) : RuntimeException("The archive names its catalog's $field '$named', not '$given'; an import never renames a catalog.")

/** The archive's release pins releases the tenant does not have ([missing]), and the archive does not carry them. */
class CatalogImportDependenciesMissingException(
    val catalogKey: CatalogKey,
    val version: String,
    val missing: List<app.epistola.suite.environments.MissingReleaseDependency>,
) : RuntimeException("${catalogKey.value} $version renders with releases that cannot be had: " + missing.joinToString())

/** What filling a version from an archive does with resources the version has and the archive does not. */
enum class DraftFillMode {
    /** Overlay: upsert the archive's resources, keep the others (default). */
    MERGE,

    /** Mirror: also delete the others (conflict-checked). A restore does this. */
    REPLACE,
}

data class ImportCatalogZipResult(
    val catalogKey: CatalogKey,
    val catalogName: String,
    val results: List<InstallResult>,
    /**
     * True when a resource of a received release FAILED to install, so stale resources were NOT
     * pruned and `installed_*` was NOT advanced — the catalog stays on its previous release and a
     * re-import retries (parity with `UpgradeCatalog`, never a silent half-upgrade).
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
    private val rows: CatalogImportRows,
    private val nextVersionChanges: app.epistola.suite.catalog.releases.NextVersionChanges,
    private val installer: CatalogResourceInstaller,
    private val receivedReleases: ReceivedReleases,
    private val fingerprints: app.epistola.suite.catalog.CatalogFingerprintService,
    private val releaseIdentity: app.epistola.suite.catalog.revisions.ReleaseIdentity,
    private val catalogNames: app.epistola.suite.catalog.CatalogNamesApplier,
) : CommandHandler<ImportCatalogZip, ImportCatalogZipResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ImportCatalogZip): ImportCatalogZipResult {
        if (command.intoDraft || command.restore) return importArchive(command)
        return install(command)
    }

    /** What [handle] does for an archive: receives its release (see [ImportCatalogZip]). */
    private fun install(command: ImportCatalogZip): ImportCatalogZipResult {
        val parsed = parse(command)
        val manifest = parsed.manifest
        val catalogKey = CatalogKey.of(manifest.catalog.key)
        // The archive's name: its own (wire v8), or the one the caller gave a migrated one.
        // Both are required from wire v8, and a migrated archive has the caller's (the migrator and the
        // validator enforce it), so neither is null here.
        val registry = requireNotNull(manifest.catalog.registry) { "The archive's catalog names no registry" }
        val namespace = requireNotNull(manifest.catalog.namespace) { "The archive's catalog names no namespace" }
        command.registry?.let { if (it != registry) throw CatalogNameMismatchException("registry", it, registry) }
        command.namespace?.let { if (it != namespace) throw CatalogNameMismatchException("namespace", it, namespace) }
        // Its key means the catalog of its namespace here: another namespace's catalog with the same
        // key is another catalog, beside this one (ADR 0034, phase 4).
        return CatalogNames.within(declaredNamespaces(manifest)) { catalogNames.named(catalogKey, namespace) { install(command, parsed, catalogKey, registry, namespace) } }
    }

    private fun install(command: ImportCatalogZip, parsed: Parsed, catalogKey: CatalogKey, registry: String, namespace: String): ImportCatalogZipResult {
        val manifest = parsed.manifest
        val existing = GetCatalog(command.tenantKey, catalogKey).query()
        if (existing != null && (existing.registry != registry || existing.namespace != namespace)) {
            throw CatalogImportConflictException(
                "This tenant has a catalog '${catalogKey.value}' in ${existing.registry}/${existing.namespace}; the archive names $registry/$namespace/${catalogKey.value}, another catalog.",
            )
        }
        val version = manifest.release.version
        val published = manifest.release.fingerprint

        // The releases it carries, before it: each one this tenant lacks is received; one it has is
        // left alone.
        val installedDependencies = mutableListOf<String>()
        val presentDependencies = mutableListOf<String>()
        for ((release, bytes) in parsed.carried) {
            val label = "${release.catalogKey} v${release.releaseVersion}"
            // A release it carries from before wire v8 is in the namespace the caller named for it.
            val dependency = install(ImportCatalogZip(tenantKey = command.tenantKey, zipBytes = bytes, confirmMigration = true, legacyName = LegacyCatalogName(registry, namespace)))
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

        // What the archive's release is (ADR 0034): its content hash, the V6 fingerprint it names, else
        // computed from what it carries (an archive migrated from before V6).
        val bound by lazy {
            parsed.manifest.resources.associate { entry ->
                val detail = parsed.entries[entry.detailUrl.removePrefix("./")]
                    ?: throw IllegalArgumentException("Missing resource detail: ${entry.detailUrl}")
                "${entry.type}/${entry.key}" to schemaMigrator.migrateAndBindResourceDetail(entry.type, detail, parsed.catalogCtx).resource
            }
        }
        val incomingHash by lazy { published?.takeIf(app.epistola.suite.catalog.CatalogCanonicalizer::isContentHash) ?: releaseIdentity.contentHashOf(publishedContent(manifest, bound, parsed.entries)) }
        val present = existing?.let { ListCatalogReleaseSummaries(command.tenantKey, catalogKey).query().firstOrNull { it.version == version } }
        if (present != null) {
            // The same release, by its content, whatever fingerprint either side was recorded with.
            if (published == null || jdbi.withHandle<Boolean, Exception> { releaseIdentity.isRelease(it, command.tenantKey, catalogKey, version, incomingHash) }) return result(newly = false)
            throw CatalogReleaseTakenException(
                "This tenant has ${catalogKey.value} $version with other content (fingerprint ${present.fingerprint}, the archive's $published); one number names one release.",
            )
        }

        val missing = jdbi.withHandle<List<app.epistola.suite.environments.MissingReleaseDependency>, Exception> { handle ->
            manifest.catalogDependencies.orEmpty().map(CatalogDependencyDeclaration::of)
                .filter { it.pinned && !dependencyStore.pinHolds(handle, command.tenantKey, it) }
                .map { app.epistola.suite.environments.MissingReleaseDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion!!, app.epistola.suite.environments.MissingDependencyReason.UNAVAILABLE) }
        }
        if (missing.isNotEmpty() && !command.restoreRelease) throw CatalogImportDependenciesMissingException(catalogKey, version, missing)
        // The release is kept as it says it is (its id, rendering defaults, base), or refused before
        // anything is written.
        receivedReleases.requireKeepable(command.tenantKey, catalogKey, manifest.release)

        val highest = existing?.highestReleaseVersion?.let(SemVer::parseOrNull)
        val incoming = SemVer.parseOrNull(version)
        // Recorded beside the others (contract `installCatalogRelease`): a release below the highest
        // one, or any while the next version has changes of its own (ADR 0032), is recorded from the
        // archive as published; the next version stays, behind it.
        val older = highest != null && incoming != null && incoming < highest
        if (existing != null && (command.restoreRelease || older || nextVersionChanges.hasChanges(command.tenantKey, catalogKey))) {
            RecordInstalledRelease(
                command.tenantKey,
                catalogKey,
                published = publishedContent(manifest, bound, parsed.entries),
                release = RecordedRelease(version, published),
                received = manifest.release,
            ).execute()
            return result(newly = true)
        }
        val imported = importArchive(command)
        if (imported.aborted) {
            // Nothing was advanced: a catalog the tenant had stays on the release it had, and one this
            // import created is removed again, so a failed install leaves nothing behind and the retry
            // finds the key free (all or nothing).
            if (existing == null) DeleteCatalog(command.tenantKey, catalogKey, force = true).execute()
            return imported.copy(releaseVersion = version, newlyInstalled = false, installedDependencies = installedDependencies, presentDependencies = presentDependencies)
        }
        return result(newly = true)
    }

    private data class Parsed(
        val entries: Map<String, ByteArray>,
        val manifest: app.epistola.catalog.protocol.CatalogManifest,
        val catalogCtx: app.epistola.suite.catalog.migrations.CatalogMigrationContext,
        /** The releases the archive carries for its pins, each as an archive of its own, in install order. */
        val carried: List<Pair<app.epistola.catalog.archive.EmbeddedCatalogRelease, ByteArray>> = emptyList(),
    )

    private fun parse(command: ImportCatalogZip): Parsed {
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
        // An archive from before wire v8 names no registry or namespace: it takes the ones the caller
        // names, and without them is refused (contract ADR 0003: the migrator never invents them).
        // The reader migrates it, so it is told here, not only the manifest binding below.
        val migration = CatalogMigrationOptions(
            namespace = command.namespace ?: command.legacyName?.namespace,
            registry = command.registry ?: command.legacyName?.registry,
        )
        val read = CatalogArchiveReader.read(
            ByteArrayInputStream(command.zipBytes),
            CatalogArchivePolicy(
                maxCompressedBytes = maxZipSize,
                maxExpandedBytes = maxDecompressedSize,
            ),
            migration,
        )
        val unnamed = read.findings.filter { it.code == CatalogMigrationCodes.REGISTRY_MISSING || it.code == CatalogMigrationCodes.NAMESPACE_MISSING }
        if (unnamed.isNotEmpty()) {
            throw CatalogNameRequiredException(unnamed.map { if (it.code == CatalogMigrationCodes.REGISTRY_MISSING) "registry" else "namespace" }.distinct())
        }
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
        // An archive from before wire v8 takes the name the caller gave it (ADR 0003).
        val migratedManifest = schemaMigrator.migrateAndBindManifest(manifestBytes, migration.namespace, migration.registry)
        val manifest = migratedManifest.manifest
        val catalogCtx = migratedManifest.catalog
        // Content names other catalogs by alias; this installation stores references by catalog key,
        // so an alias that is not the key cannot be stored as it is received (DependencyAliases).
        DependencyAliases.unsupported(manifest.catalogDependencies.orEmpty()).takeIf { it.isNotEmpty() }?.let { refused ->
            throw CatalogImportValidationException(
                refused.map { (path, message) -> CatalogImportValidationFinding(DependencyAliases.ALIAS_UNSUPPORTED, ValidationSeverity.ERROR, path, message) },
            )
        }
        // Decide what to do about an outdated-schema archive (a source-less transport we can't
        // re-fetch): one with no migration path is rejected; one the chain can upgrade prompts for
        // confirmation (migrating mutates content) and then imports, whoever receives it.
        // `sourceVersion` is the pre-migration version; `manifest.schemaVersion` is current iff a
        // chain upgraded it. Covers the UI and REST import paths.
        when (
            CatalogImportSchemaAction.decide(
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

    /**
     * Writes the archive's content into the version the transaction targets: a received release's
     * fast-forward into the next version (pruning what the release no longer has and moving the
     * next version to it), or, internally, a draft filled ([ImportCatalogZip.intoDraft]) or a
     * catalog restored ([ImportCatalogZip.restore]).
     */
    private fun importArchive(command: ImportCatalogZip): ImportCatalogZipResult {
        val parsed = parse(command)
        val namespace = parsed.manifest.catalog.namespace ?: return importArchive(command, parsed)
        return CatalogNames.within(declaredNamespaces(parsed.manifest)) {
            catalogNames.named(CatalogKey.of(parsed.manifest.catalog.key), namespace) { importArchive(command, parsed) }
        }
    }

    /**
     * The namespaces of the catalogs [manifest] declares, by the key its content names each by (its
     * alias, which is its key here): what its references mean, whatever other namespace has a catalog
     * with that key (ADR 0034, phase 4).
     */
    private fun declaredNamespaces(manifest: app.epistola.catalog.protocol.CatalogManifest): Map<String, String> = manifest.catalogDependencies.orEmpty()
        .associate { dependency -> dependency.catalogKey to dependency.namespace }

    private fun importArchive(command: ImportCatalogZip, parsed: Parsed): ImportCatalogZipResult = CatalogImportContext.runAsImport {
        val (entries, manifest, catalogCtx) = parsed
        val catalogKey = CatalogKey.of(manifest.catalog.key)
        val receiving = !command.intoDraft && !command.restore
        // #692: bound the manifest name to catalogs.name VARCHAR(255) before it is written.
        validateCatalogNameLength(manifest.catalog.name)

        val existingCatalog = GetCatalog(command.tenantKey, catalogKey).query()

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

        val manifestSlugs = manifest.resources.groupBy({ it.type }, { it.key })

        // Stale-prune cases: a received release (always — the archive is what the next version
        // moves to), and a REPLACE into an existing catalog (mirror it exactly). Both validate
        // cross-catalog conflicts BEFORE any mutation — a stale resource still referenced from
        // another catalog blocks the whole import. (New catalog ⇒ nothing installed ⇒ no stale ⇒ no
        // conflicts; MERGE keeps the version's other resources, so never prunes.)
        val prunesStale = receiving || (existingCatalog != null && command.fillMode == DraftFillMode.REPLACE)
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

        // A catalog the tenant does not have is created as its archive names it (contract ADR 0003),
        // with the source it came from; the database opens its next version.
        if (existingCatalog == null) {
            rows.insertCatalog(
                command.tenantKey,
                catalogKey,
                manifest.catalog.name,
                manifest.catalog.description,
                command.sourceUrl,
                registry = manifest.catalog.registry ?: identity.defaultRegistry,
                namespace = manifest.catalog.namespace ?: CatalogIdentityProperties.defaultNamespace(command.tenantKey),
            )
        }

        val ordered = manifest.resources
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
        // Install resources in dependency order. What each binds to is kept as well: a received
        // release is recorded from the archive as published, not from the rows it is put in place as.
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

                val status = installer.install(
                    tenantKey = command.tenantKey,
                    catalogKey = catalogKey,
                    resource = resource,
                    version = manifest.release.version,
                    binaries = entries::get,
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

        if (receiving) {
            // A received release IS the next version's upgrade — same contract as a URL
            // `UpgradeCatalog`, the archive just being the transport instead of a source URL.
            if (anyFailed) {
                // Abort: do NOT prune stale or advance installed_* — the catalog stays on its
                // previous release and a re-import retries (never a silent half-upgrade).
                val failed = results.filter { it.status == InstallStatus.FAILED }
                logger.error(
                    "Receiving '{}' {} ABORTED — {} resource(s) failed; not pruning stale, not advancing installed version: {}",
                    catalogKey.value,
                    manifest.release.version,
                    failed.size,
                    failed.joinToString { "${it.type}/${it.slug}: ${it.errorMessage}" },
                )
                return@runAsImport ImportCatalogZipResult(catalogKey, manifest.catalog.name, results, aborted = true)
            }
            analyzer.removeStale(command.tenantKey, catalogKey, staleBefore)
            // Moves the next version to the release (the database's trigger on installed_release_version).
            rows.updateInstalledState(
                command.tenantKey,
                catalogKey,
                manifest.release.version,
                manifest.release.fingerprint,
                fingerprints.storeBaseline(sourceResourceFingerprints(manifest, entries)),
                manifest.catalog.name,
                manifest.catalog.description,
                catalogMetadataJson,
            )
            jdbi.useHandle<Exception> { dependencyStore.replace(it, command.tenantKey, catalogKey, declared) }
            rows.updateThemeSettings(command.tenantKey, catalogKey, manifest.catalog, objectMapper)
            markReady(command.tenantKey, catalogKey, manifest.resources)
            if (!InstalledReleaseRecording.isDeferred) {
                RecordInstalledRelease(command.tenantKey, catalogKey, published = publishedContent(manifest, bound, entries), received = manifest.release).execute()
            }
        } else if (!anyFailed) {
            if (!command.intoDraft) {
                rows.updateMetadata(
                    command.tenantKey,
                    catalogKey,
                    manifest.catalog.name,
                    manifest.catalog.description,
                    catalogMetadataJson,
                )
            }
            // The archive's declarations, locked as it was cut. A restore's MERGE keeps those it does not name.
            jdbi.useHandle<Exception> { handle ->
                val kept = if (existingCatalog != null && command.fillMode != DraftFillMode.REPLACE) {
                    dependencyStore.declarations(handle, command.tenantKey, catalogKey).filter { local -> declared.none { it.identity == local.identity } }
                } else {
                    emptyList()
                }
                dependencyStore.replace(handle, command.tenantKey, catalogKey, declared + kept)
            }
            // A merge into existing work keeps its theme settings; a fresh or replacing import, or one
            // filling a draft (a release copied into it), takes the archive's.
            if (existingCatalog == null || command.fillMode == DraftFillMode.REPLACE || command.intoDraft) {
                rows.updateThemeSettings(command.tenantKey, catalogKey, manifest.catalog, objectMapper)
            }
            markReady(command.tenantKey, catalogKey, manifest.resources)
        }

        if (!receiving && existingCatalog != null && command.fillMode == DraftFillMode.REPLACE) {
            // REPLACE an existing catalog's version (a restore): make it exactly the archive by
            // pruning the version's other resources (already conflict-checked above). On a failed
            // install we DON'T prune (avoid a half-replace).
            if (anyFailed) {
                logger.warn(
                    "REPLACE of '{}' — {} resource(s) failed; NOT pruning local-only resources (avoiding a half-replace)",
                    catalogKey.value,
                    results.count { it.status == InstallStatus.FAILED },
                )
            } else {
                val removed = analyzer.removeStale(command.tenantKey, catalogKey, staleBefore)
                logger.info("REPLACE of '{}' — {} local-only resource(s) pruned", catalogKey.value, removed.size)
            }
        }

        // Record the wholesale content-set point — reached only on non-aborted
        // paths (the receiving abort returns early above). NOW() here is after
        // the resource-install loop, so it is ≥ every imported resource's
        // updated_at: a no-op re-import advances this in lockstep and does NOT
        // register as drift; only a later edit does. With released_at
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
     * Marks [resources] ready in the version the transaction targets: what an archive brings is
     * released content, ready as it is (contract ADR 0002).
     */
    private fun markReady(tenantKey: TenantKey, catalogKey: CatalogKey, resources: List<app.epistola.catalog.protocol.ResourceEntry>) {
        jdbi.useHandle<Exception> { handle ->
            for (entry in resources) {
                readyMarks.resourceId(handle, tenantKey, catalogKey, entry.type, entry.key)
                    ?.let { readyMarks.mark(handle, tenantKey, it) }
            }
        }
    }

    /**
     * The received release's per-resource baseline from the ZIP bytes, by the same function as a URL
     * source's ([app.epistola.suite.catalog.CatalogFingerprintService.resourceFingerprintsFromSource],
     * the complete form), keyed `"$type/$key"`, asset bytes folded in.
     */
    private fun sourceResourceFingerprints(
        manifest: CatalogManifest,
        entries: Map<String, ByteArray>,
    ): Map<String, String> {
        val detailBytesByKey = manifest.resources.mapNotNull { entry ->
            entries[entry.detailUrl.removePrefix("./")]?.let { "${entry.type}/${entry.key}" to it }
        }.toMap()
        return fingerprints.resourceFingerprints(detailBytesByKey) { contentUrl ->
            entries[contentUrl.removePrefix("./")]
        }
    }
}
