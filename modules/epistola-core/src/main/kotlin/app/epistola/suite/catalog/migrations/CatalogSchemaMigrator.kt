// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.migrations

import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationFinding
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.PublisherInfo
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.protocol.ResourceDetail
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import app.epistola.catalog.migration.CatalogMigrationContext as PortableMigrationContext
import app.epistola.catalog.migration.CatalogSchemaMigrator as PortableSchemaMigrator

/** Suite exception adapter for epistola-catalog's portable schema migrator. */
@Component
class CatalogSchemaMigrator() {
    @Suppress("UNUSED_PARAMETER")
    constructor(objectMapper: ObjectMapper, migrations: List<Any>) : this()

    fun migrateAndBindManifest(rawManifest: ByteArray): MigratedManifest {
        val result = PortableSchemaMigrator.migrateManifest(ByteArrayInputStream(rawManifest))
        val manifest = result.value ?: throw result.findings.first().asSuiteException()
        val sourceVersion = requireNotNull(result.sourceVersion)
        return MigratedManifest(
            manifest = manifest,
            catalog = CatalogMigrationContext(sourceVersion, manifest),
        )
    }

    fun migrateAndBindResourceDetail(
        type: String,
        rawDetail: ByteArray,
        catalog: CatalogMigrationContext,
    ): ResourceDetail {
        val result = PortableSchemaMigrator.migrateResourceDetail(
            type,
            ByteArrayInputStream(rawDetail),
            PortableMigrationContext(catalog.sourceVersion, catalog.manifest),
            "resource detail '$type'",
        )
        return result.value ?: throw result.findings.first().asSuiteException()
    }

    private fun CatalogMigrationFinding.asSuiteException(): CatalogSchemaException = when (code) {
        CatalogMigrationCodes.SCHEMA_TOO_NEW -> CatalogSchemaTooNewException(
            version = versionInMessage() ?: CatalogWireSchema.CURRENT_VERSION + 1,
            current = CatalogWireSchema.CURRENT_VERSION,
        )
        CatalogMigrationCodes.SCHEMA_TOO_OLD -> CatalogSchemaTooOldException(
            version = versionInMessage() ?: CatalogWireSchema.BASELINE_VERSION - 1,
            baseline = CatalogWireSchema.BASELINE_VERSION,
        )
        else -> CatalogSchemaUnknownException(message)
    }

    private fun CatalogMigrationFinding.versionInMessage(): Int? = Regex("""schemaVersion (-?\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
}

data class CatalogMigrationContext(
    val sourceVersion: Int,
    val manifest: CatalogManifest,
) {
    @Suppress("UNUSED_PARAMETER")
    constructor(sourceVersion: Int, migratedManifest: ObjectNode) : this(sourceVersion, PLACEHOLDER_MANIFEST)

    companion object {
        private val PLACEHOLDER_MANIFEST = CatalogManifest(
            schemaVersion = CatalogWireSchema.CURRENT_VERSION,
            catalog = CatalogInfo("migration", "Migration"),
            publisher = PublisherInfo("Epistola Suite"),
            release = ReleaseInfo("0.0.0"),
            resources = emptyList(),
        )
    }
}

data class MigratedManifest(
    val manifest: CatalogManifest,
    val catalog: CatalogMigrationContext,
)
