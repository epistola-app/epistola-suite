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
import tools.jackson.core.type.TypeReference
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

/**
 * Thrown when narrowing allowed values would invalidate existing variants.
 */
class AllowedValuesInUseException(
    val attributeId: AttributeId,
    val removedValues: Set<String>,
) : RuntimeException(
    "Cannot remove allowed values ${removedValues.joinToString(", ") { "'$it'" }} from attribute '${attributeId.value}': " +
        "existing variants still use these values. Update the variants first.",
)

@Component
class UpdateAttributeDefinitionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(command: UpdateAttributeDefinition): VariantAttributeDefinition? = jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
        // If allowed values are being narrowed, check for existing variants using removed values
        if (command.allowedValues.isNotEmpty()) {
            val currentAllowedValues = handle.createQuery(
                """
                    SELECT allowed_values FROM variant_attribute_definitions
                    WHERE id = :id AND tenant_id = :tenantId
                    """,
            )
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)
                .mapTo(String::class.java)
                .findOne()
                .orElse(null) ?: return@withHandle null

            val currentValues: List<String> = objectMapper.readValue(
                currentAllowedValues,
                object : TypeReference<List<String>>() {},
            )

            val removedValues = currentValues.toSet() - command.allowedValues.toSet()
            if (removedValues.isNotEmpty()) {
                // Check if any variants use the values being removed
                val valuesInUse = removedValues.filter { value ->
                    handle.createQuery(
                        """
                            SELECT COUNT(*) FROM template_variants
                            WHERE tenant_id = :tenantId
                              AND attributes ->> :attributeKey = :value
                            """,
                    )
                        .bind("tenantId", command.tenantId)
                        .bind("attributeKey", command.id.value)
                        .bind("value", value)
                        .mapTo(Long::class.java)
                        .one() > 0
                }.toSet()

                if (valuesInUse.isNotEmpty()) {
                    throw AllowedValuesInUseException(command.id, valuesInUse)
                }
            }
        }

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
