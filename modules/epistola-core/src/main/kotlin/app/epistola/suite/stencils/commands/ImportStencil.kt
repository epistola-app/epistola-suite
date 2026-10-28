// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Installs one stencil carried in a catalog archive as the stencil's working copy.
 *
 * A stencil has no versions (contract ADR 0002, ADR 0006): importing replaces its content,
 * parameter schema and whether it reads the template's data. An import whose content and schema
 * equal what is stored is `SKIPPED`; otherwise the stencil is `INSTALLED` or `UPDATED`.
 */
data class ImportStencil(
    val catalog: CatalogId,
    val slug: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
    val parameterSchema: Map<String, Any?>? = null,
    val readsTemplateData: Boolean = false,
    /** Producer metadata (contract `CatalogResource.metadata`), as received; null keeps what the draft holds. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceiling (#692): stencils.name is VARCHAR(255).
        validate("name", name.length <= MAX_NAME_COLUMN_LENGTH) { "Name must be $MAX_NAME_COLUMN_LENGTH characters or less" }
    }
}

@Component
class ImportStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templateDocumentValidator: TemplateDocumentValidator,
    private val stencils: app.epistola.suite.stencils.StencilStore,
) : CommandHandler<ImportStencil, InstallStatus> {

    override fun handle(command: ImportStencil): InstallStatus {
        templateDocumentValidator.validateStencil(command.content, command.slug, command.catalogKey.value)
        // Wire content is relative to the catalog it lands in, so qualify against the target. The
        // same JSON backs the comparison and the write, so a re-import still compares equal.
        val contentJson = objectMapper.valueToTree<JsonNode>(command.content)
            .also { ResourceReferenceSites.qualifyRelative(it, command.catalogKey.value) }
            .let(objectMapper::writeValueAsString)
        val parameterSchemaJson = command.parameterSchema?.let { objectMapper.writeValueAsString(it) }
        val auditUser = currentUserIdOrNull()?.value

        val id = app.epistola.suite.common.ids.StencilId(StencilKey.of(command.slug), command.catalog)
        val content = objectMapper.readValue(contentJson, app.epistola.template.model.TemplateDocument::class.java)
        val parameterSchema = parameterSchemaJson?.let(objectMapper::readTree)
        return jdbi.withHandle<InstallStatus, Exception> { handle ->
            val current = stencils.get(handle, id)
            // Compared by value, as the stored content would be.
            val unchanged = current?.let {
                objectMapper.valueToTree<JsonNode>(it.content) == objectMapper.valueToTree<JsonNode>(content) &&
                    it.parameterSchema == parameterSchema && it.readsTemplateData == command.readsTemplateData
            }
            stencils.put(handle, id, app.epistola.suite.stencils.StencilFields(command.name, command.description, command.tags, content, parameterSchema, command.readsTemplateData, command.metadata), auditUser)
            when (unchanged) {
                null -> InstallStatus.INSTALLED
                true -> InstallStatus.SKIPPED
                false -> InstallStatus.UPDATED
            }
        }
    }
}
