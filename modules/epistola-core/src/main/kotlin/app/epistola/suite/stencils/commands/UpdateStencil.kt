// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.PresetUse
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.stencils.STENCIL_COLUMNS
import app.epistola.suite.stencils.STENCIL_FROM
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.StencilStore
import app.epistola.suite.stencils.declaresNoParameters
import app.epistola.suite.stencils.fields
import app.epistola.suite.stencils.withAbsentSchemaAsNull
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.validation.ParameterSchemaValidator
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Updates a stencil: its metadata (name, description, tags) and its working copy (content,
 * parameter schema, whether it reads the template's data). Each is changed only when given.
 * Returns null if the stencil doesn't exist.
 */
data class UpdateStencil(
    val id: StencilId,
    val name: String? = null,
    /** The new description; null keeps the current one, blank removes it (as [clearDescription] does). */
    val description: String? = null,
    val tags: List<String>? = null,
    val clearDescription: Boolean = false,
    val content: TemplateDocument? = null,
    val parameterSchema: JsonNode? = null,
    val clearParameterSchema: Boolean = false,
    val readsTemplateData: Boolean? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The stencil's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<Stencil?>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        name?.let {
            validate("name", it.isNotBlank()) { "Name cannot be blank" }
            validate("name", it.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
            // Load-bearing for the stencil-picker stored-XSS fix — don't relax without reading it.
            validate("name", it.none { c -> c == '<' || c == '>' || c.isISOControl() }) {
                "Name must not contain '<', '>', or control characters"
            }
        }
        description?.let {
            validate("description", it.length <= 1000) { "Description must be 1000 characters or less" }
        }
        tags?.let {
            validate("tags", it.size <= 20) { "Maximum 20 tags allowed" }
            it.forEachIndexed { i, tag ->
                validate("tags[$i]", tag.length <= 50) { "Tag must be 50 characters or less" }
                validate("tags[$i]", tag.none { c -> c == '<' || c == '>' || c.isISOControl() }) {
                    "Tag must not contain '<', '>', or control characters"
                }
            }
        }
    }
}

@Component
class UpdateStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templateDocumentValidator: TemplateDocumentValidator,
    private val parameterSchemaValidator: ParameterSchemaValidator,
    private val usedCatalogs: UsedCatalogDeclarer,
    private val stencils: StencilStore,
) : CommandHandler<UpdateStencil, Stencil?> {
    override fun handle(command: UpdateStencil): Stencil? {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        if (saved != null) command.content?.let { usedCatalogs.declare(command.id.catalogId, DependencyScanner.crossCatalogRefs(it, command.id.catalogKey.value)) }
        return saved
    }

    private fun save(command: UpdateStencil): Stencil? {
        requireCatalogEditable(command.id.catalogId)
        command.content?.let { templateDocumentValidator.validateStencil(it, command.id.key.value, command.id.catalogKey.value) }
        command.content?.let { PresetUse.requireAvailable(command.id.catalogId, it) }
        val clearsParameterSchema = command.clearParameterSchema || declaresNoParameters(command.parameterSchema)
        if (!clearsParameterSchema) parameterSchemaValidator.validate(command.parameterSchema)
        return jdbi.withHandle<Stencil?, Exception> { handle ->
            // Locked, so the change is applied to what is there, and two writers do not race.
            val revision = DraftResources.lockRevision(handle, command.id.catalogId, StencilStore.STENCIL, command.id.key.value) ?: return@withHandle null
            Revisions.refuse("Stencil '${command.id.key.value}'", command.expectedRevision, revision)
            val current = checkNotNull(stencils.get(handle, command.id)) { "Stencil ${command.id.key.value} is locked, so it is there" }
            val changes = command.name != null || command.clearDescription || command.description != null || command.tags != null ||
                command.content != null || clearsParameterSchema || command.parameterSchema != null || command.readsTemplateData != null
            if (!changes) return@withHandle current
            val fields = current.fields().copy(
                name = command.name ?: current.name,
                // Blank removes it, as clearDescription does (as UpdateDocumentTemplate's description).
                description = when {
                    command.clearDescription || command.description?.isBlank() == true -> null
                    command.description != null -> command.description
                    else -> current.description
                },
                tags = command.tags ?: current.tags,
                // Stored references name the catalog they resolve against (ResourceReferenceSites).
                content = command.content?.let { content ->
                    objectMapper.valueToTree<JsonNode>(content)
                        .also { ResourceReferenceSites.qualifyRelative(it, command.id.catalogKey.value) }
                        .let { objectMapper.treeToValue(it, app.epistola.template.model.TemplateDocument::class.java) }
                } ?: current.content,
                parameterSchema = if (clearsParameterSchema) null else command.parameterSchema ?: current.parameterSchema,
                readsTemplateData = command.readsTemplateData ?: current.readsTemplateData,
            )
            if (!stencils.write(handle, command.id, fields, currentUserIdOrNull()?.value)) return@withHandle null
            stencils.get(handle, command.id)
        }
    }
}
