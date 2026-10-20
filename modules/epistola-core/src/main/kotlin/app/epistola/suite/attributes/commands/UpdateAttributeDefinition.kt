// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.AttributeReferences
import app.epistola.suite.attributes.codelists.boundCodeListAtAddress
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.Revisions
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
    /** The attribute's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogVersionScoped,
    Command<VariantAttributeDefinition?>,
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
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<UpdateAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(command: UpdateAttributeDefinition): VariantAttributeDefinition? {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        if (saved != null) usedCatalogs.declare(command.id.tenantKey, command.id.catalogKey, command.codeListId?.catalogId?.key)
        return saved
    }

    private fun save(command: UpdateAttributeDefinition): VariantAttributeDefinition? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
            // If allowed values are being narrowed, check for existing variants using removed values
            if (command.allowedValues.isNotEmpty()) {
                val currentAllowedValues = handle.createQuery(
                    """
                    SELECT allowed_values FROM variant_attribute_definitions
                    WHERE id = :id AND tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")}
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
                              AND ${inCurrentVersion("variants")}
                              AND ${AttributeReferences.usesValue("variants", "value")}
                            """,
                        )
                            .bind("tenantId", command.id.tenantKey)
                            .bind(AttributeReferences.QUALIFIED_PARAM, AttributeReferences.qualifiedKey(command.id))
                            .bind(AttributeReferences.BARE_PARAM, AttributeReferences.bareKey(command.id))
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
                WHERE id = :id AND tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")}
                  AND ${Revisions.MATCHES}
                RETURNING id, tenant_key, display_name, allowed_values, created_at, updated_at, revision,
                          (SELECT cat.catalog_key FROM catalogs cat
                            WHERE cat.tenant_key = variant_attribute_definitions.tenant_key
                              AND cat.catalog_id = variant_attribute_definitions.catalog_id) AS catalog_key,
                          (SELECT cl_cat.catalog_key FROM code_lists cl
                             JOIN catalogs cl_cat ON cl_cat.tenant_key = cl.tenant_key AND cl_cat.catalog_id = cl.catalog_id
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id
                              AND ${inCurrentVersion("cl")}) AS code_list_catalog_key,
                          (SELECT slug FROM code_lists cl
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id
                              AND ${inCurrentVersion("cl")}) AS code_list_slug
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("displayName", command.displayName)
                .bind("allowedValues", allowedValuesJson)
                .bind("codeListCatalogKey", command.codeListId?.catalogKey)
                .bind("codeListSlug", command.codeListId?.key)
                .bind("expectedRevision", command.expectedRevision)
                .mapTo<VariantAttributeDefinition>()
                .findOne()
                .orElse(null)
                ?: run {
                    Revisions.refuse("Attribute '${command.id.key.value}'", command.expectedRevision, handle.contentRevision("variant_attribute_definitions", "id", command.id.tenantKey, command.id.catalogKey, command.id.key))
                    null
                }
        }
    }
}
