// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.findByTenantAndId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class RegisterCatalog(
    override val tenantKey: TenantKey,
    val sourceUrl: String,
    val authType: AuthType = AuthType.NONE,
    val authCredential: String? = null,
) : Command<Catalog>,
    RequiresPermission,
    // Fetches the remote catalog manifest over HTTP mid-command.
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class RegisterCatalogHandler(
    private val jdbi: Jdbi,
    private val catalogClient: CatalogClient,
    private val fingerprintService: CatalogFingerprintService,
    private val objectMapper: ObjectMapper,
) : CommandHandler<RegisterCatalog, Catalog> {

    override fun handle(command: RegisterCatalog): Catalog {
        val manifest = catalogClient.fetchManifest(
            command.sourceUrl,
            command.authType,
            command.authCredential,
        )

        val catalogKey = CatalogKey.of(manifest.catalog.slug)
        // #692: bound the manifest name to catalogs.name VARCHAR(255) before insert.
        validateCatalogNameLength(manifest.catalog.name)

        // Source-side per-resource baseline, captured at register exactly like
        // installed_fingerprint (never publisher-authored). The upgrade preview
        // diffs this against the re-fetched manifest — source-vs-source, so a
        // CHANGED verdict means the publisher changed that resource.
        val resourceFingerprintsJson = objectMapper.writeValueAsString(
            fingerprintService.perResourceFingerprintsFromSource(
                command.sourceUrl,
                command.authType,
                command.authCredential,
            ),
        )

        return jdbi.inTransaction<Catalog, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, description, type, source_url, source_auth_type, source_auth_credential, installed_release_version, installed_fingerprint, installed_resource_fingerprints, created_at, updated_at)
                VALUES (:id, :tenantKey, :name, :description, 'SUBSCRIBED', :sourceUrl, :authType, :authCredential, :releaseVersion, :fingerprint, :resourceFingerprints::jsonb, NOW(), NOW())
                ON CONFLICT (tenant_key, id) DO UPDATE
                SET name = :name, description = :description, source_url = :sourceUrl, source_auth_type = :authType,
                    source_auth_credential = :authCredential, installed_release_version = :releaseVersion,
                    installed_fingerprint = :fingerprint, installed_resource_fingerprints = :resourceFingerprints::jsonb, updated_at = NOW()
                """,
            )
                .bind("id", catalogKey)
                .bind("tenantKey", command.tenantKey)
                .bind("name", manifest.catalog.name)
                .bind("description", manifest.catalog.description)
                .bind("sourceUrl", command.sourceUrl)
                .bind("authType", command.authType.name)
                .bind("authCredential", command.authCredential?.let(::Secret))
                .bind("releaseVersion", manifest.release.version)
                .bind("fingerprint", manifest.release.fingerprint)
                .bind("resourceFingerprints", resourceFingerprintsJson)
                .execute()

            handle.findByTenantAndId<Catalog>("catalogs", command.tenantKey, catalogKey.value)!!
        }
    }
}
