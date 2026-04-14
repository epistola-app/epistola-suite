package app.epistola.suite.attributes.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Thrown when attempting to delete an attribute definition that is still referenced by variants.
 */
class AttributeInUseException(
    val attributeId: AttributeId,
    val variantCount: Long,
) : RuntimeException(
    "Cannot delete attribute '${attributeId.key.value}': it is still referenced by $variantCount variant(s). " +
        "Remove the attribute from all variants first.",
)

data class DeleteAttributeDefinition(
    val id: AttributeId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey get() = id.tenantKey
}

@Component
class DeleteAttributeDefinitionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteAttributeDefinition, Boolean> {
    override fun handle(command: DeleteAttributeDefinition): Boolean {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.withHandle<Boolean, Exception> { handle ->
        // Check if any variants still reference this attribute
        val variantCount = handle.createQuery(
            """
                SELECT COUNT(*) FROM template_variants
                WHERE tenant_key = :tenantId AND jsonb_exists(attributes, :attributeKey)
                """,
        )
            .bind("tenantId", command.id.tenantKey)
            .bind("attributeKey", command.id.key.value)
            .mapTo(Long::class.java)
            .one()

        if (variantCount > 0) {
            throw AttributeInUseException(command.id, variantCount)
        }

        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM variant_attribute_definitions
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", command.id.key)
            .bind("tenantId", command.id.tenantKey)
            .execute()
        rowsAffected > 0
        }
    }
}
