package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates an attribute definition.
 *
 * The attribute is constrained in exactly one of three ways:
 *  1. Free format        — `allowedValues` is empty and `codeListId` is null
 *  2. Inline values      — `allowedValues` non-empty and `codeListId` null
 *  3. Bound to code list — `codeListId` non-null and `allowedValues` empty
 *
 * The DB FK on `(tenant_key, code_list_catalog_key, code_list_slug)` and a
 * `CHECK` constraint enforce the binding. Modes (2) and (3) are mutually
 * exclusive — guarded here as well so we fail before reaching the database.
 *
 * The `tenantId` carried by the FK target is implicit: `codeListId.tenantKey`
 * must equal `id.tenantKey` (caller's responsibility — the command validates
 * it explicitly).
 */
data class CreateAttributeDefinition(
    val id: AttributeId,
    val displayName: String,
    val allowedValues: List<String> = emptyList(),
    val codeListId: CodeListId? = null,
) : Command<VariantAttributeDefinition>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey

    init {
        validate("displayName", displayName.isNotBlank()) { "Display name is required" }
        validate("displayName", displayName.length <= 100) { "Display name must be 100 characters or less" }
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

@Component
class CreateAttributeDefinitionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateAttributeDefinition, VariantAttributeDefinition> {
    override fun handle(command: CreateAttributeDefinition): VariantAttributeDefinition {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return executeOrThrowDuplicate("attribute", command.id.key.value) {
            jdbi.withHandle<VariantAttributeDefinition, Exception> { handle ->
                val allowedValuesJson = objectMapper.writeValueAsString(command.allowedValues)

                handle.createQuery(
                    """
                INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_key, display_name, allowed_values,
                                                           code_list_catalog_key, code_list_slug,
                                                           created_at, updated_at)
                VALUES (:id, :tenantId, :catalogKey, :displayName, :allowedValues::jsonb,
                        :codeListCatalogKey, :codeListSlug,
                        NOW(), NOW())
                RETURNING *
                """,
                )
                    .bind("id", command.id.key)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("displayName", command.displayName)
                    .bind("allowedValues", allowedValuesJson)
                    .bind("codeListCatalogKey", command.codeListId?.catalogKey)
                    .bind("codeListSlug", command.codeListId?.key)
                    .mapTo<VariantAttributeDefinition>()
                    .one()
            }
        }
    }
}
