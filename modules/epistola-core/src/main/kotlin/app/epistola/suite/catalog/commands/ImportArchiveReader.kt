// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.archive.CatalogArchivePolicy
import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationOptions
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.validation.CatalogValidationPolicy
import app.epistola.catalog.validation.CatalogValidator
import app.epistola.catalog.validation.ValidationSeverity
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogImportSchemaAction
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.dependencies.DependencyAliases
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream

/** An import's archive, read and checked: its entries, its manifest on the current wire, and the releases it carries. */
internal data class ParsedCatalogArchive(
    val entries: Map<String, ByteArray>,
    val manifest: app.epistola.catalog.protocol.CatalogManifest,
    val catalogCtx: app.epistola.suite.catalog.migrations.CatalogMigrationContext,
    /** The releases the archive carries for its pins, each as an archive of its own, in install order. */
    val carried: List<Pair<app.epistola.catalog.archive.EmbeddedCatalogRelease, ByteArray>> = emptyList(),
)

/**
 * Reads and checks an import's archive before anything is written ([ImportCatalogZip]): its size, the
 * catalog it names, its schema version and the releases it carries for its pins.
 */
@Component
class ImportArchiveReader(
    private val sizeLimits: CatalogSizeLimits,
    private val schemaMigrator: CatalogSchemaMigrator,
) {
    internal fun read(command: ImportCatalogZip): ParsedCatalogArchive {
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
        return ParsedCatalogArchive(entries, manifest, catalogCtx, carried)
    }
}
