package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class RegisterCatalog(
    override val tenantKey: TenantKey,
    val sourceUrl: String,
    val authType: AuthType = AuthType.NONE,
    val authCredential: String? = null,
) : Command<Catalog>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class RegisterCatalogHandler(
    private val jdbi: Jdbi,
    private val catalogClient: CatalogClient,
) : CommandHandler<RegisterCatalog, Catalog> {

    override fun handle(command: RegisterCatalog): Catalog {
        val manifest = catalogClient.fetchManifest(
            command.sourceUrl,
            command.authType,
            command.authCredential,
        )

        val catalogKey = CatalogKey.of(manifest.catalog.slug)

        return jdbi.inTransaction<Catalog, Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (id, tenant_key, name, description, type, source_url, source_auth_type, source_auth_credential, installed_release_version, created_at, last_modified)
                VALUES (:id, :tenantKey, :name, :description, 'IMPORTED', :sourceUrl, :authType, :authCredential, :releaseVersion, NOW(), NOW())
                ON CONFLICT (tenant_key, id) DO UPDATE
                SET name = :name, description = :description, source_url = :sourceUrl, source_auth_type = :authType,
                    source_auth_credential = :authCredential, installed_release_version = :releaseVersion, last_modified = NOW()
                """,
            )
                .bind("id", catalogKey)
                .bind("tenantKey", command.tenantKey)
                .bind("name", manifest.catalog.name)
                .bind("description", manifest.catalog.description)
                .bind("sourceUrl", command.sourceUrl)
                .bind("authType", command.authType.name)
                .bind("authCredential", command.authCredential)
                .bind("releaseVersion", manifest.release.version)
                .execute()

            handle.createQuery(
                """
                SELECT id, tenant_key, name, description, type, source_url, source_auth_type, source_auth_credential, installed_release_version, installed_at, created_at, last_modified
                FROM catalogs
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", catalogKey)
                .mapTo<Catalog>()
                .one()
        }
    }
}
