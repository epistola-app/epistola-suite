// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogMetadata
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.outbound.CatalogSourceProperties
import app.epistola.suite.catalog.outbound.OutboundUrlGuard
import app.epistola.suite.catalog.outbound.OutboundUrlRejectedException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.executeOrThrowDuplicate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Adds the catalog whose manifest is at [sourceUrl] to a tenant, with that manifest as its source.
 *
 * Refused when the tenant has a catalog under the manifest's key already: it is that catalog's to
 * receive releases ([UpgradeCatalog], an import), and registering never takes it over (ADR 0032).
 *
 * The URL is checked against the outbound source rules before anything is fetched
 * ([OutboundUrlGuard.checkSource]): `classpath:` only for catalogs Epistola ships, `file:` only
 * inside the configured roots, network sources only on public addresses. User input and the
 * bundled system and demo catalogs take the same path and the same rules.
 */
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
    private val objectMapper: ObjectMapper,
    private val guard: OutboundUrlGuard,
    private val properties: CatalogSourceProperties,
    private val identity: CatalogIdentityProperties,
) : CommandHandler<RegisterCatalog, Catalog> {

    override fun handle(command: RegisterCatalog): Catalog {
        try {
            CatalogClient.validateUrl(command.sourceUrl, setOf("https", "http", "file", "classpath"))
            guard.checkSource(command.sourceUrl, properties.allowHttp)
        } catch (e: OutboundUrlRejectedException) {
            throw ValidationException("sourceUrl", e.message, ValidationCode.SOURCE_URL_NOT_ALLOWED)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("sourceUrl", e.message ?: "The catalog URL is not valid", ValidationCode.SOURCE_URL_NOT_ALLOWED)
        }
        val manifest = catalogClient.fetchManifest(
            command.sourceUrl,
            command.authType,
            command.authCredential,
        )

        val catalogKey = CatalogKey.of(manifest.catalog.key)
        // #692: bound the manifest name to catalogs.name VARCHAR(255) before insert.
        validateCatalogNameLength(manifest.catalog.name)

        val catalogMetadataJson = objectMapper.writeValueAsString(CatalogMetadata.from(manifest.catalog))

        // Its key means the catalog of its own namespace here, beside one of another (ADR 0034, phase 4).
        val namespace = manifest.catalog.namespace ?: CatalogIdentityProperties.defaultNamespace(command.tenantKey)
        return executeOrThrowDuplicate("catalog", catalogKey.value) {
            register(command, manifest, CatalogId(namespace, catalogKey, TenantId(command.tenantKey)), catalogMetadataJson)
        }
    }

    private fun register(
        command: RegisterCatalog,
        manifest: app.epistola.catalog.protocol.CatalogManifest,
        catalog: CatalogId,
        catalogMetadataJson: String,
    ): Catalog = jdbi.inTransaction<Catalog, Exception> { handle ->
        // The database opens the catalog's next version; it is on no release until one is installed.
        handle.createUpdate(
            """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, description, source_url, source_auth_type, source_auth_credential, catalog_metadata, created_at, updated_at)
                VALUES (:id, :tenantKey, :registry, :namespace, :name, :description, :sourceUrl, :authType, :authCredential, :catalogMetadata::jsonb, NOW(), NOW())
                """,
        )
            .bind("id", catalog.key)
            .bind("tenantKey", command.tenantKey)
            // A catalog from a manifest is named by its publisher (contract ADR 0003).
            .bind("registry", manifest.catalog.registry ?: identity.defaultRegistry)
            .bind("namespace", catalog.namespace)
            .bind("name", manifest.catalog.name)
            .bind("description", manifest.catalog.description)
            .bind("sourceUrl", command.sourceUrl)
            .bind("authType", command.authType.name)
            .bind("authCredential", command.authCredential?.let(::Secret))
            .bind("catalogMetadata", catalogMetadataJson)
            .execute()
        // What its release declares arrives with the release (the install): on no release yet, the next
        // version would hold them as changes of its own.
        handle.findCatalog(catalog)!!
    }
}
