package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.templates.validation.ParameterSchemaValidator
import app.epistola.suite.templates.validation.PlaceholderValidator
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
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
    val parameterSchema: JsonNode? = null,
) : Command<Stencil>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
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
    private val placeholderValidator: PlaceholderValidator,
    private val parameterSchemaValidator: ParameterSchemaValidator,
) : CommandHandler<CreateStencil, Stencil> {
    override fun handle(command: CreateStencil): Stencil {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        if (command.content != null) placeholderValidator.validateAsStencilDefinition(command.content)
        parameterSchemaValidator.validate(command.parameterSchema)
        val auditUser = currentUserIdOrNull()?.value
        return executeOrThrowDuplicate("stencil", command.id.key.value) {
            jdbi.inTransaction<Stencil, Exception> { handle ->
                val tagsJson = objectMapper.writeValueAsString(command.tags)

                // 1. Create the stencil
                val stencil = handle.createQuery(
                    """
                    INSERT INTO stencils (id, tenant_key, catalog_key, name, description, tags, created_at, updated_at, created_by, updated_by)
                    VALUES (:id, :tenantId, :catalogKey, :name, :description, :tags::jsonb, NOW(), NOW(), :createdBy, :updatedBy)
                    RETURNING id, tenant_key, catalog_key, name, description, tags, created_at, updated_at, created_by, updated_by
                    """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("name", command.name)
                    .bind("description", command.description)
                    .bind("tags", tagsJson)
                    .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                    .mapTo<Stencil>()
                    .one()

                // 2. Create initial draft version (empty if no content provided)
                run {
                    val content = command.content ?: emptyTemplateDocument()
                    val contentJson = objectMapper.writeValueAsString(content)
                    val parameterSchemaJson = command.parameterSchema?.let { objectMapper.writeValueAsString(it) }
                    handle.createUpdate(
                        """
                        INSERT INTO stencil_versions (id, tenant_key, catalog_key, stencil_key, content, parameter_schema, status, created_at, created_by)
                        VALUES (:id, :tenantId, :catalogKey, :stencilId, :content::jsonb, :parameterSchema::jsonb, 'draft', NOW(), :createdBy)
                        """,
                    )
                        .bind("id", VersionKey.of(1))
                        .bind("tenantId", command.id.tenantKey)
                        .bind("catalogKey", command.id.catalogKey)
                        .bind("stencilId", command.id.key)
                        .bind("content", contentJson)
                        .bind("parameterSchema", parameterSchemaJson)
                        .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                        .execute()
                }

                stencil
            }
        }
    }
}

private fun emptyTemplateDocument(): TemplateDocument {
    val rootId = "root"
    val slotId = "slot-root"
    return TemplateDocument(
        modelVersion = 1,
        root = rootId,
        nodes = mapOf(
            rootId to Node(id = rootId, type = "root", slots = listOf(slotId)),
        ),
        slots = mapOf(
            slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
