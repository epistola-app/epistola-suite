package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates a new stencil. If content is provided, a draft version (version 1)
 * is automatically created with that content.
 */
data class CreateStencil(
    val id: StencilId,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument? = null,
) : Command<Stencil>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= 255) { "Name must be 255 characters or less" }
        description?.let {
            validate("description", it.length <= 1000) { "Description must be 1000 characters or less" }
        }
        validate("tags", tags.size <= 20) { "Maximum 20 tags allowed" }
        tags.forEachIndexed { i, tag ->
            validate("tags[$i]", tag.length <= 50) { "Tag must be 50 characters or less" }
        }
    }
}

@Component
class CreateStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateStencil, Stencil> {
    override fun handle(command: CreateStencil): Stencil = executeOrThrowDuplicate("stencil", command.id.key.value) {
        jdbi.inTransaction<Stencil, Exception> { handle ->
            val tagsJson = objectMapper.writeValueAsString(command.tags)

            // 1. Create the stencil
            val stencil = handle.createQuery(
                """
                INSERT INTO stencils (id, tenant_key, name, description, tags, created_at, last_modified)
                VALUES (:id, :tenantId, :name, :description, :tags::jsonb, NOW(), NOW())
                RETURNING id, tenant_key, name, description, tags, created_at, last_modified
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .bind("tags", tagsJson)
                .mapTo<Stencil>()
                .one()

            // 2. If content is provided, create initial draft version
            command.content?.let { content ->
                val contentJson = objectMapper.writeValueAsString(content)
                handle.createUpdate(
                    """
                    INSERT INTO stencil_versions (id, tenant_key, stencil_key, content, status, created_at)
                    VALUES (:id, :tenantId, :stencilId, :content::jsonb, 'draft', NOW())
                    """,
                )
                    .bind("id", VersionKey.of(1))
                    .bind("tenantId", command.id.tenantKey)
                    .bind("stencilId", command.id.key)
                    .bind("content", contentJson)
                    .execute()
            }

            stencil
        }
    }
}
