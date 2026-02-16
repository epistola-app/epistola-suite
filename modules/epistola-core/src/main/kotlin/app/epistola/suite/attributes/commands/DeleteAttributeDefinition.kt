package app.epistola.suite.attributes.commands

import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteAttributeDefinition(
    val id: AttributeId,
    val tenantId: TenantId,
) : Command<Boolean>

@Component
class DeleteAttributeDefinitionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteAttributeDefinition, Boolean> {
    override fun handle(command: DeleteAttributeDefinition): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM variant_attribute_definitions
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .execute()
        rowsAffected > 0
    }
}
