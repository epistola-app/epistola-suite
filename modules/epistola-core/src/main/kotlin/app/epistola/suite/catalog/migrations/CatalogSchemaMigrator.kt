// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.migrations

import app.epistola.catalog.migration.CatalogMigrationCodes
import app.epistola.catalog.migration.CatalogMigrationFinding
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import app.epistola.catalog.migration.CatalogMigrationContext as PortableMigrationContext
import app.epistola.catalog.migration.CatalogSchemaMigrator as PortableSchemaMigrator

/** Suite exception adapter for epistola-catalog's portable schema migrator. */
@Component
class CatalogSchemaMigrator {
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

    private fun CatalogMigrationFinding.asSuiteException(): CatalogSchemaException = asSuiteException(code, message)

    internal fun asSuiteException(
        code: String,
        message: String,
    ): CatalogSchemaException = when (code) {
        CatalogMigrationCodes.SCHEMA_TOO_NEW -> CatalogSchemaTooNewException(
            version = message.versionInMessage() ?: CatalogWireSchema.CURRENT_VERSION + 1,
            current = CatalogWireSchema.CURRENT_VERSION,
        )
        CatalogMigrationCodes.SCHEMA_TOO_OLD -> CatalogSchemaTooOldException(
            version = message.versionInMessage() ?: CatalogWireSchema.BASELINE_VERSION - 1,
            baseline = CatalogWireSchema.BASELINE_VERSION,
        )
        else -> CatalogSchemaUnknownException(
            if (message.startsWith("resource detail is at schemaVersion")) {
                "$message; every part of a catalog must carry the same wire version"
            } else {
                message
            },
        )
    }

    private fun String.versionInMessage(): Int? = Regex("""schemaVersion (-?\d+)""").find(this)?.groupValues?.get(1)?.toIntOrNull()
}

data class CatalogMigrationContext(
    val sourceVersion: Int,
    val manifest: CatalogManifest,
)

data class MigratedManifest(
    val manifest: CatalogManifest,
    val catalog: CatalogMigrationContext,
)
