// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.catalog.versions.PresetUse
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.stencils.STENCIL_COLUMNS
import app.epistola.suite.stencils.STENCIL_FROM
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.declaresNoParameters
import app.epistola.suite.stencils.stencilAtAddress
import app.epistola.suite.stencils.withAbsentSchemaAsNull
import app.epistola.suite.templates.validation.ParameterSchemaValidator
import app.epistola.suite.templates.validation.TemplateDocumentValidator
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Creates a new stencil with its working copy: [content], or an empty document.
 */
data class CreateStencil(
    val id: StencilId,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument? = null,
    val parameterSchema: JsonNode? = null,
    /** The stencil's content also sees the using template's data (contract ADR 0006, part 5). */
    val readsTemplateData: Boolean = false,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<Stencil>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        // Load-bearing for the stencil-picker stored-XSS fix — don't relax without reading it.
        validate("name", name.none { it == '<' || it == '>' || it.isISOControl() }) {
            "Name must not contain '<', '>', or control characters"
        }
        description?.let {
            validate("description", it.length <= 1000) { "Description must be 1000 characters or less" }
        }
        validate("tags", tags.size <= 20) { "Maximum 20 tags allowed" }
        tags.forEachIndexed { i, tag ->
            validate("tags[$i]", tag.length <= 50) { "Tag must be 50 characters or less" }
            validate("tags[$i]", tag.none { it == '<' || it == '>' || it.isISOControl() }) {
                "Tag must not contain '<', '>', or control characters"
            }
        }
    }
}

@Component
class CreateStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val templateDocumentValidator: TemplateDocumentValidator,
    private val parameterSchemaValidator: ParameterSchemaValidator,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<CreateStencil, Stencil> {
    override fun handle(command: CreateStencil): Stencil {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        command.content?.let { usedCatalogs.declare(command.id.tenantKey, command.id.catalogKey, DependencyScanner.crossCatalogRefs(it, command.id.catalogKey.value)) }
        return saved
    }

    private fun save(command: CreateStencil): Stencil {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        if (command.content != null) {
            templateDocumentValidator.validateStencil(command.content, command.id.key.value, command.id.catalogKey.value)
            PresetUse.requireAvailable(command.id.tenantKey, command.id.catalogKey, command.content)
        }
        val parameterSchema = command.parameterSchema?.takeUnless(::declaresNoParameters)
        parameterSchemaValidator.validate(parameterSchema)
        val auditUser = currentUserIdOrNull()?.value
        return executeOrThrowDuplicate("stencil", command.id.key.value) {
            jdbi.inTransaction<Stencil, Exception> { handle ->
                val tagsJson = objectMapper.writeValueAsString(command.tags)

                val content = command.content ?: emptyTemplateDocument()
                // Stored references name the catalog they resolve against, so relocation cannot
                // change their meaning (ResourceReferenceSites).
                val contentJson = objectMapper.valueToTree<JsonNode>(content)
                    .also { ResourceReferenceSites.qualifyRelative(it, command.id.catalogKey.value) }
                    .let(objectMapper::writeValueAsString)
                handle.createUpdate(
                    """
                    INSERT INTO stencils (id, tenant_key, catalog_id, version_id, name, description, tags, content, parameter_schema, reads_template_data,
                                          created_at, updated_at, created_by, updated_by)
                    VALUES (:id, :tenantId, ${catalogAt("tenantId", "catalogKey")}, ${versionAt("tenantId", "catalogKey")}, :name, :description, :tags::jsonb, :content::jsonb,
                            :parameterSchema::jsonb, :readsTemplateData, NOW(), NOW(), :createdBy, :updatedBy)
                    """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("name", command.name)
                    .bind("description", command.description)
                    .bind("tags", tagsJson)
                    .bind("content", contentJson)
                    .bind("parameterSchema", parameterSchema?.let { objectMapper.writeValueAsString(it) })
                    .bind("readsTemplateData", command.readsTemplateData)
                    .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                    .execute()
                val stencil = handle.createQuery("SELECT $STENCIL_COLUMNS $STENCIL_FROM WHERE s.tenant_key = :tenantId AND s.version_id = ${versionAt("tenantId", "catalogKey")} AND s.id = :id")
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .mapTo<Stencil>()
                    .map(Stencil::withAbsentSchemaAsNull)
                    .one()

                stencil
            }
        }
    }
}

private fun emptyTemplateDocument(): TemplateDocument {
    val rootId = "root"
    val slotId = "slot-root"
    return TemplateDocument(
        modelVersion = 1,
        root = rootId,
        nodes = mapOf(
            rootId to Node(id = rootId, type = "root", slots = listOf(slotId)),
        ),
        slots = mapOf(
            slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )
}
