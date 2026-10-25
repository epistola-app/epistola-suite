// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.codelists.boundCodeListAtAddress
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<VariantAttributeDefinition>,
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

@Component
class CreateAttributeDefinitionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<CreateAttributeDefinition, VariantAttributeDefinition> {
    override fun handle(command: CreateAttributeDefinition): VariantAttributeDefinition {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        usedCatalogs.declare(command.id.catalogId, command.codeListId?.catalogId)
        return saved
    }

    private fun save(command: CreateAttributeDefinition): VariantAttributeDefinition {
        requireCatalogEditable(command.id.catalogId)
        return executeOrThrowDuplicate("attribute", command.id.key.value) {
            jdbi.withHandle<VariantAttributeDefinition, Exception> { handle ->
                val allowedValuesJson = objectMapper.writeValueAsString(command.allowedValues)

                handle.createQuery(
                    """
                INSERT INTO variant_attribute_definitions (id, tenant_key, catalog_id, version_id, display_name, allowed_values,
                                                           code_list_resource_id,
                                                           created_at, updated_at)
                VALUES (:id, :tenantId, ${catalogAt("tenantId", "catalogKey")}, ${versionAt("tenantId", "catalogKey")}, :displayName, :allowedValues::jsonb,
                        ${boundCodeListAtAddress("tenantId")},
                        NOW(), NOW())
                RETURNING id, tenant_key, display_name, allowed_values, created_at, updated_at,
                          (SELECT cat.catalog_key FROM catalogs cat
                            WHERE cat.tenant_key = variant_attribute_definitions.tenant_key
                              AND cat.catalog_id = variant_attribute_definitions.catalog_id) AS catalog_key,
                          (SELECT cat.namespace FROM catalogs cat
                            WHERE cat.tenant_key = variant_attribute_definitions.tenant_key
                              AND cat.catalog_id = variant_attribute_definitions.catalog_id) AS namespace,
                          (SELECT cl_cat.catalog_key FROM current_code_lists cl
                             JOIN catalogs cl_cat ON cl_cat.tenant_key = cl.tenant_key AND cl_cat.catalog_id = cl.catalog_id
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id) AS code_list_catalog_key,
                          (SELECT cl_cat.namespace FROM current_code_lists cl
                             JOIN catalogs cl_cat ON cl_cat.tenant_key = cl.tenant_key AND cl_cat.catalog_id = cl.catalog_id
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id) AS code_list_catalog_namespace,
                          (SELECT cl.id FROM current_code_lists cl
                            WHERE cl.tenant_key = variant_attribute_definitions.tenant_key
                              AND cl.resource_id = variant_attribute_definitions.code_list_resource_id) AS code_list_slug
                """,
                )
                    .bind("id", command.id.key)
                    .bindCatalog("catalogKey", command.id.catalogId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("displayName", command.displayName)
                    .bind("allowedValues", allowedValuesJson)
                    .bindCatalog("codeListCatalogKey", command.codeListId?.catalogId)
                    .bind("codeListSlug", command.codeListId?.key)
                    .mapTo<VariantAttributeDefinition>()
                    .one()
            }
        }
    }
}
