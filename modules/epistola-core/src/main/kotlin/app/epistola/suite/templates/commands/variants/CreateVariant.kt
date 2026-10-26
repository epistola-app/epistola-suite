// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.StoredVariant
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.createDefaultTemplateModel
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.templateVariant
import app.epistola.suite.validation.DuplicateIdException
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateVariant(
    val id: VariantId,
    val title: String,
    val description: String?,
    val attributes: Map<String, String> = emptyMap(),
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<TemplateVariant?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("title", title.isNotBlank()) { "Title is required" }
        validate("title", title.length <= MAX_NAME_LENGTH) { "Title must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class CreateVariantHandler(
    private val jdbi: Jdbi,
    private val templateDocumentPreparation: TemplateDocumentPreparation,
    private val usedCatalogs: app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer,
    private val templates: TemplateStore,
) : CommandHandler<CreateVariant, TemplateVariant?> {
    override fun handle(command: CreateVariant): TemplateVariant? {
        requireCatalogEditable(command.id.catalogId)

        val auditUser = currentUserIdOrNull()?.value

        // Validate attributes against the tenant's attribute definitions
        validateAttributes(command.id.tenantId, command.attributes)
        // An attribute of another catalog names it by alias (`system.locale`): that catalog is declared now,
        // as every write that starts using another catalog declares it (contract ADR 0003).
        usedCatalogs.declareAttributes(command.id.catalogId, command.attributes.keys)

        return jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
            // A new variant starts with the empty document.
            val prepared = templateDocumentPreparation.prepare(createDefaultTemplateModel(command.id.key), command.id.catalogKey)
            val model = templates.storedModel(prepared.templateModelJson)
            templates.modify(handle, command.id.templateId, auditUser, null, "Template '${command.id.templateKey.value}'") { template ->
                if (template.variant(command.id.key.value) != null) throw DuplicateIdException("variant", command.id.key.value)
                requireDistinctAttributeSet(template, command.id.key.value, command.attributes)
                template.copy(
                    variants = template.variants + StoredVariant(
                        key = command.id.key.value,
                        title = command.title,
                        description = command.description?.takeIf { it.isNotBlank() },
                        attributes = command.attributes,
                        // The first variant of a template becomes its default.
                        isDefault = template.variants.isEmpty(),
                        model = model,
                    ),
                )
            } ?: return@inTransaction null
            templateVariant(handle, command.id)
        }
    }
}
