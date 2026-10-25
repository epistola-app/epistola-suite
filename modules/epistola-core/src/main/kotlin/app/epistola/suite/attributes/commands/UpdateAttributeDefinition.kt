// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.AttributeFields
import app.epistola.suite.attributes.AttributeReferences
import app.epistola.suite.attributes.AttributeStore
import app.epistola.suite.attributes.codelists.boundCodeListAtAddress
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
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
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The attribute's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
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
    private val attributes: AttributeStore,
) : CommandHandler<UpdateAttributeDefinition, VariantAttributeDefinition?> {
    override fun handle(command: UpdateAttributeDefinition): VariantAttributeDefinition? {
        // The code list's catalog is declared first: the binding names it by alias (ADR 0033).
        usedCatalogs.declare(command.id.catalogId, command.codeListId?.catalogId)
        return save(command)
    }

    private fun save(command: UpdateAttributeDefinition): VariantAttributeDefinition? {
        requireCatalogEditable(command.id.catalogId)
        return jdbi.withHandle<VariantAttributeDefinition?, Exception> { handle ->
            // Locked, so the change is applied to what is there, and two writers do not race.
            val revision = DraftResources.lockRevision(handle, command.id.catalogId, AttributeStore.ATTRIBUTE, command.id.key.value) ?: return@withHandle null
            Revisions.refuse("Attribute '${command.id.key.value}'", command.expectedRevision, revision)
            val current = checkNotNull(attributes.fields(handle, command.id)) { "Attribute ${command.id.key.value} is locked, so it is there" }
            // If allowed values are being narrowed, check for existing variants using removed values
            if (command.allowedValues.isNotEmpty()) {
                val currentValues: List<String> = current.allowedValues

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

            val binding = command.codeListId?.let { attributes.bindingOf(handle, command.id.catalogId, it) }
            if (!attributes.write(handle, command.id, AttributeFields(command.displayName, command.allowedValues, binding), currentUserIdOrNull()?.value)) return@withHandle null
            attributes.get(handle, command.id)
        }
    }
}
