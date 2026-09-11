// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.codelists.boundCodeListAtAddress
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateJoin
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

data class UpdateAttributeDefinition(
    val id: AttributeId,
    val displayName: String,
    val allowedValues: List<String> = emptyList(),
    val codeListId: CodeListId? = null,
) : Command<VariantAttributeDefinition?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey

    init {
        validate("displayName", displayName.isNotBlank()) { "Display name is required" }
        validate("displayName", displayName.length <= MAX_NAME_LENGTH) { "Display name must be $MAX_NAME_LENGTH characters or less" }
        validate("allowedValues", allowedValues.all { it.isNotBlank() }) { "Allowed values must not be blank" }
        validate("allowedValues", allowedValues.size == allowedValues.distinct().size) { "Allowed values must be unique" }
        validate(
            "codeListId",
            codeListId == null || allowedValues.isEmpty(),
        ) { "An attribute cannot have both inline allowedValues and a bound code list" }
        validate(
            "codeListId",
            codeListId == null || codeListId.tenantKey == id.tenantKey,
        ) { "Bound code list must live in the same tenant as the attribute" }
    }
}

/**
 * Thrown when narrowing allowed values would invalidate existing variants.
 */
class AllowedValuesInUseException(
    val attributeId: AttributeId,
    val removedValues: Set<String>,
) : RuntimeException(
    "Cannot remove allowed values ${removedValues.joinToString(", ") { "'$it'" }} from attribute '${attributeId.key.value}': " +
        "existing variants still use these values. Update the variants first.",
)

@Component
class UpdateAttributeDefinitionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(command: UpdateAttributeDefinition): VariantAttributeDefinition? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
            // If allowed values are being narrowed, check for existing variants using removed values
            if (command.allowedValues.isNotEmpty()) {
                val currentAllowedValues = handle.createQuery(
                    """
                    SELECT allowed_values FROM variant_attribute_definitions
                    WHERE id = :id AND tenant_key = :tenantId AND catalog_key = :catalogKey
                    """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
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
                            SELECT COUNT(*) FROM template_variants variants
                            ${templateJoin("variants")}
                            WHERE variants.tenant_key = :tenantId
                              AND template.catalog_key = :catalogKey
                              AND variants.attributes ->> :attributeKey = :value
                            """,
                        )
                            .bind("tenantId", command.id.tenantKey)
                            .bind("catalogKey", command.id.catalogKey)
                            .bind("attributeKey", command.id.key.value)
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
                SET display_name           = :displayName,
                    allowed_values         = :allowedValues::jsonb,
                    code_list_resource_id  = ${boundCodeListAtAddress("tenantId")},
                    updated_at          = NOW()
                WHERE id = :id AND tenant_key = :tenantId AND catalog_key = :catalogKey
                RETURNING id, tenant_key, catalog_key, display_name, allowed_values, created_at, updated_at,
                          (SELECT catalog_key FROM code_lists cl
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id) AS code_list_catalog_key,
                          (SELECT slug FROM code_lists cl
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id) AS code_list_slug
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("displayName", command.displayName)
                .bind("allowedValues", allowedValuesJson)
                .bind("codeListCatalogKey", command.codeListId?.catalogKey)
                .bind("codeListSlug", command.codeListId?.key)
                .mapTo<VariantAttributeDefinition>()
                .findOne()
                .orElse(null)
        }
    }
}
