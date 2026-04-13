package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class ImportStencil(
    val tenantId: TenantId,
    val slug: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ImportStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ImportStencil, InstallStatus> {

    override fun handle(command: ImportStencil): InstallStatus {
        val stencilKey = StencilKey.of(command.slug)
        val tagsJson = objectMapper.writeValueAsString(command.tags)
        val contentJson = objectMapper.writeValueAsString(command.content)

        return jdbi.inTransaction<InstallStatus, Exception> { handle ->
            val exists = handle.createQuery("SELECT COUNT(*) > 0 FROM stencils WHERE id = :id AND tenant_key = :tenantKey")
                .bind("id", stencilKey)
                .bind("tenantKey", command.tenantKey)
                .mapTo(Boolean::class.java)
                .one()

            // Upsert stencil
            handle.createUpdate(
                """
                INSERT INTO stencils (id, tenant_key, catalog_key, name, description, tags, created_at, last_modified)
                VALUES (:id, :tenantKey, :catalogKey, :name, :description, :tags::jsonb, NOW(), NOW())
                ON CONFLICT (tenant_key, catalog_key, id) DO UPDATE
                SET name = :name, description = :description, tags = :tags::jsonb, last_modified = NOW()
                """,
            )
                .bind("id", stencilKey)
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", CatalogKey.DEFAULT)
                .bind("name", command.name)
                .bind("description", command.description)
                .bind("tags", tagsJson)
                .execute()

            // Upsert draft version: update existing draft or create version 1
            val updated = handle.createUpdate(
                """
                UPDATE stencil_versions SET content = :content::jsonb
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND stencil_key = :stencilKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", CatalogKey.DEFAULT)
                .bind("stencilKey", stencilKey)
                .bind("content", contentJson)
                .execute()

            if (updated == 0) {
                val nextId = handle.createQuery(
                    "SELECT COALESCE(MAX(id), 0) + 1 FROM stencil_versions WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND stencil_key = :stencilKey",
                )
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", CatalogKey.DEFAULT)
                    .bind("stencilKey", stencilKey)
                    .mapTo(Int::class.java)
                    .one()

                handle.createUpdate(
                    """
                    INSERT INTO stencil_versions (id, tenant_key, catalog_key, stencil_key, content, status, created_at)
                    VALUES (:id, :tenantKey, :catalogKey, :stencilKey, :content::jsonb, 'draft', NOW())
                    """,
                )
                    .bind("id", VersionKey.of(nextId))
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", CatalogKey.DEFAULT)
                    .bind("stencilKey", stencilKey)
                    .bind("content", contentJson)
                    .execute()
            }

            if (exists) InstallStatus.UPDATED else InstallStatus.INSTALLED
        }
    }
}
