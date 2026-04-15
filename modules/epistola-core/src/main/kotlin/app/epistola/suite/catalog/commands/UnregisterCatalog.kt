package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogInUseException
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class UnregisterCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class UnregisterCatalogHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UnregisterCatalog, Boolean> {

    override fun handle(command: UnregisterCatalog): Boolean {
        require(command.catalogKey != CatalogKey.DEFAULT) {
            "The default catalog cannot be deleted"
        }

        return jdbi.inTransaction<Boolean, Exception> { handle ->
            // Check for cross-catalog references before deleting
            val references = findCrossReferences(handle, command.tenantKey, command.catalogKey)
            if (references.isNotEmpty()) {
                throw CatalogInUseException(command.catalogKey, references)
            }

            val deleted = handle.createUpdate(
                """
                DELETE FROM catalogs
                WHERE tenant_key = :tenantKey AND id = :id
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("id", command.catalogKey)
                .execute()

            deleted > 0
        }
    }

    private fun findCrossReferences(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<String> {
        val references = mutableListOf<String>()

        // 1. Templates in other catalogs that reference themes from this catalog
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, dt.catalog_key
            FROM document_templates dt
            WHERE dt.tenant_key = :tenantKey
              AND dt.theme_catalog_key = :catalogKey
              AND dt.catalog_key != :catalogKey
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .map { rs, _ ->
                "Template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")}) uses a theme"
            }
            .list()
            .let { references.addAll(it) }

        // 2. Templates in other catalogs that use stencils from this catalog
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, tv.catalog_key
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            WHERE tv.tenant_key = :tenantKey
              AND tv.catalog_key != :catalogKey
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'stencil'
              AND n.value -> 'props' ->> 'catalogKey' = :catalogKeyStr
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .bind("catalogKeyStr", catalogKey.value)
            .map { rs, _ ->
                "Template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")}) uses a stencil"
            }
            .list()
            .let { references.addAll(it) }

        // 3. Templates in other catalogs that use assets from this catalog
        handle.createQuery(
            """
            SELECT DISTINCT dt.name, tv.catalog_key
            FROM template_versions tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
            CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS n(key, value)
            JOIN assets a ON a.tenant_key = tv.tenant_key AND a.catalog_key = :catalogKey AND a.id::text = n.value -> 'props' ->> 'assetId'
            WHERE tv.tenant_key = :tenantKey
              AND tv.catalog_key != :catalogKey
              AND tv.status IN ('draft', 'published')
              AND n.value ->> 'type' = 'image'
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .map { rs, _ ->
                "Template '${rs.getString("name")}' (catalog: ${rs.getString("catalog_key")}) uses an asset"
            }
            .list()
            .let { references.addAll(it) }

        return references
    }
}
