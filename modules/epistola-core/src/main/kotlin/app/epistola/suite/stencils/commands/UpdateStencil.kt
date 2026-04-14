package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Updates a stencil's metadata (name, description, tags).
 * Returns null if the stencil doesn't exist.
 */
data class UpdateStencil(
    val id: StencilId,
    val name: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val clearDescription: Boolean = false,
) : Command<Stencil?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        name?.let {
            validate("name", it.isNotBlank()) { "Name cannot be blank" }
            validate("name", it.length <= 255) { "Name must be 255 characters or less" }
        }
        description?.let {
            validate("description", it.length <= 1000) { "Description must be 1000 characters or less" }
        }
        tags?.let {
            validate("tags", it.size <= 20) { "Maximum 20 tags allowed" }
            it.forEachIndexed { i, tag ->
                validate("tags[$i]", tag.length <= 50) { "Tag must be 50 characters or less" }
            }
        }
    }
}

@Component
class UpdateStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateStencil, Stencil?> {
    override fun handle(command: UpdateStencil): Stencil? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.inTransaction<Stencil?, Exception> { handle ->
        val setClauses = mutableListOf<String>()
        val bindings = mutableMapOf<String, Any?>()

        command.name?.let {
            setClauses.add("name = :name")
            bindings["name"] = it
        }

        if (command.clearDescription) {
            setClauses.add("description = NULL")
        } else {
            command.description?.let {
                setClauses.add("description = :description")
                bindings["description"] = it
            }
        }

        command.tags?.let {
            setClauses.add("tags = :tags::jsonb")
            bindings["tags"] = objectMapper.writeValueAsString(it)
        }

        if (setClauses.isEmpty()) {
            // Nothing to update, just return current state
            return@inTransaction handle.createQuery(
                "SELECT * FROM stencils WHERE tenant_key = :tenantId AND id = :id",
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("id", command.id.key)
                .mapTo<Stencil>()
                .findOne()
                .orElse(null)
        }

        setClauses.add("last_modified = NOW()")

        val sql = """
            UPDATE stencils SET ${setClauses.joinToString(", ")}
            WHERE tenant_key = :tenantId AND id = :id
            RETURNING *
        """

        val query = handle.createQuery(sql)
            .bind("tenantId", command.id.tenantKey)
            .bind("id", command.id.key)

        bindings.forEach { (key, value) -> query.bind(key, value) }

        query.mapTo<Stencil>()
            .findOne()
            .orElse(null)
        }
    }
}
