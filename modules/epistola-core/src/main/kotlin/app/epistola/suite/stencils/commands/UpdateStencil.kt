// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
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
    val description: String? = null,
    val tags: List<String>? = null,
    val clearDescription: Boolean = false,
    val content: TemplateDocument? = null,
    val parameterSchema: JsonNode? = null,
    val clearParameterSchema: Boolean = false,
    val readsTemplateData: Boolean? = null,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
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
) : CommandHandler<UpdateStencil, Stencil?> {
    override fun handle(command: UpdateStencil): Stencil? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        command.content?.let { templateDocumentValidator.validateStencil(it, command.id.key.value, command.id.catalogKey.value) }
        parameterSchemaValidator.validate(command.parameterSchema)
        return jdbi.inTransaction<Stencil?, Exception> { handle ->
            val setClauses = mutableListOf<String>()
            val bindings = mutableMapOf<String, Any?>()

            command.name?.let {
                setClauses.add("name = :name")
                bindings["name"] = it
            }

            if (command.clearDescription) {
                setClauses.add("description = NULL")
            } else {
                command.description?.let {
                    setClauses.add("description = :description")
                    bindings["description"] = it
                }
            }

            command.tags?.let {
                setClauses.add("tags = :tags::jsonb")
                bindings["tags"] = objectMapper.writeValueAsString(it)
            }
            command.content?.let { content ->
                // Stored references name the catalog they resolve against (ResourceReferenceSites).
                setClauses.add("content = :content::jsonb")
                bindings["content"] = objectMapper.valueToTree<JsonNode>(content)
                    .also { ResourceReferenceSites.qualifyRelative(it, command.id.catalogKey.value) }
                    .let(objectMapper::writeValueAsString)
            }
            if (command.clearParameterSchema) {
                setClauses.add("parameter_schema = NULL")
            } else {
                command.parameterSchema?.let {
                    setClauses.add("parameter_schema = :parameterSchema::jsonb")
                    bindings["parameterSchema"] = objectMapper.writeValueAsString(it)
                }
            }
            command.readsTemplateData?.let {
                setClauses.add("reads_template_data = :readsTemplateData")
                bindings["readsTemplateData"] = it
            }

            if (setClauses.isEmpty()) {
                // Nothing to update, just return current state
                return@inTransaction handle.createQuery(
                    "SELECT $STENCIL_COLUMNS $STENCIL_FROM WHERE s.tenant_key = :tenantId AND s.version_id = ${versionAt("tenantId", "catalogKey")} AND s.id = :id",
                )
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("id", command.id.key)
                    .mapTo<Stencil>()
                    .map(Stencil::withAbsentSchemaAsNull)
                    .findOne()
                    .orElse(null)
            }

            setClauses.add("updated_at = NOW()")
            setClauses.add("updated_by = :updatedBy")
            bindings["updatedBy"] = currentUserIdOrNull()?.value

            val update = handle.createUpdate(
                """
                UPDATE stencils SET ${setClauses.joinToString(", ")}
                WHERE tenant_key = :tenantId AND version_id = ${versionAt("tenantId", "catalogKey")} AND id = :id
                """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("id", command.id.key)
            bindings.forEach { (key, value) -> update.bind(key, value) }
            if (update.execute() == 0) return@inTransaction null

            handle.createQuery("SELECT $STENCIL_COLUMNS $STENCIL_FROM WHERE s.tenant_key = :tenantId AND s.version_id = ${versionAt("tenantId", "catalogKey")} AND s.id = :id")
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("id", command.id.key)
                .mapTo<Stencil>()
                .map(Stencil::withAbsentSchemaAsNull)
                .findOne()
                .orElse(null)
        }
    }
}
