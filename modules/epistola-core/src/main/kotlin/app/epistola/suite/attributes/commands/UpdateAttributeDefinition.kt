package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class UpdateAttributeDefinition(
    val id: AttributeId,
    val tenantId: TenantId,
    val displayName: String,
    val allowedValues: List<String> = emptyList(),
) : Command<VariantAttributeDefinition?> {
    init {
        validate("displayName", displayName.isNotBlank()) { "Display name is required" }
        validate("displayName", displayName.length <= 100) { "Display name must be 100 characters or less" }
        validate("allowedValues", allowedValues.all { it.isNotBlank() }) { "Allowed values must not be blank" }
        validate("allowedValues", allowedValues.size == allowedValues.distinct().size) { "Allowed values must be unique" }
    }
}

@Component
class UpdateAttributeDefinitionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(command: UpdateAttributeDefinition): VariantAttributeDefinition? = jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
        val allowedValuesJson = objectMapper.writeValueAsString(command.allowedValues)

        handle.createQuery(
            """
                UPDATE variant_attribute_definitions
                SET display_name = :displayName,
                    allowed_values = :allowedValues::jsonb,
                    last_modified = NOW()
                WHERE id = :id AND tenant_id = :tenantId
                RETURNING *
                """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .bind("displayName", command.displayName)
            .bind("allowedValues", allowedValuesJson)
            .mapTo<VariantAttributeDefinition>()
            .findOne()
            .orElse(null)
    }
}
