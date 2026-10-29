// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.ResourceMetadata
import app.epistola.suite.catalog.revisions.StoredContent
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** What a stencil says: everything its content holds but its key. */
data class StencilFields(
    val name: String,
    val description: String?,
    val tags: List<String>,
    val content: TemplateDocument,
    val parameterSchema: JsonNode?,
    val readsTemplateData: Boolean,
    /** Producer metadata to store (contract `CatalogResource.metadata`); null keeps what the draft holds, empty clears it. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
)

/** What [this] stencil says. */
fun Stencil.fields(): StencilFields = StencilFields(name, description, tags, content, parameterSchema, readsTemplateData)

/**
 * Writes the stencils of a draft (ADR 0033, step 4): `{ "resource": <StencilResource> }` in
 * `resource_contents`, with the references its document holds indexed when stored, and the draft's
 * row naming it ([DraftResources]). Read back through `current_stencils` ([STENCIL_COLUMNS]).
 */
@Component
class StencilStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
) {

    /**
     * The content of stencil [key] of catalog [catalogKey], in its released form: references into
     * its own catalog relative, without the name qualifiers no reference carries, as a release of it
     * stores it (`CatalogContentBuilder.Outbound`), so the draft and its release name one content.
     */
    fun payload(key: String, catalogKey: String, fields: StencilFields): JsonNode = objectMapper.createObjectNode().set(
        "resource",
        objectMapper.valueToTree<JsonNode>(
            StencilResource(
                key = key,
                name = fields.name,
                description = fields.description,
                tags = fields.tags,
                content = objectMapper.treeToValue(
                    app.epistola.suite.catalog.CatalogContentBuilder.Outbound(catalogKey).content(objectMapper.valueToTree(fields.content)),
                    TemplateDocument::class.java,
                ),
                parameterSchema = fields.parameterSchema?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, Map::class.java) }?.let {
                    @Suppress("UNCHECKED_CAST")
                    it as Map<String, Any?>
                },
                readsTemplateData = fields.readsTemplateData,
            ),
        ),
    )

    fun store(handle: Handle, catalog: CatalogId, key: String, fields: StencilFields): String = contents.store(handle, catalog.tenantKey, STENCIL, ResourceMetadata.carried(handle, catalog, STENCIL, key, payload(key, catalog.key.value, fields), fields.metadata, objectMapper))

    /** Adds stencil [id] to the targeted draft; one already there is a unique violation. */
    fun create(handle: Handle, id: StencilId, fields: StencilFields, actor: UUID?): Stencil {
        DraftResources.insert(handle, id.catalogId, STENCIL, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, fields.description, actor)
        return checkNotNull(get(handle, id)) { "Stencil ${id.key.value} was just written" }
    }

    /** Saves [fields] as stencil [id] of the targeted draft; false when it holds none, or it moved on. */
    fun write(handle: Handle, id: StencilId, fields: StencilFields, actor: UUID?, expectedRevision: Long? = null): Boolean = DraftResources.update(handle, id.catalogId, STENCIL, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, fields.description, actor, expectedRevision) != null

    /** Creates or replaces stencil [id] in the targeted draft; true when it was there already. */
    fun put(handle: Handle, id: StencilId, fields: StencilFields, actor: UUID?): Boolean {
        if (write(handle, id, fields, actor)) return true
        DraftResources.insert(handle, id.catalogId, STENCIL, id.key.value, store(handle, id.catalogId, id.key.value, fields), fields.name, fields.description, actor)
        return false
    }

    fun delete(handle: Handle, id: StencilId): Boolean = DraftResources.delete(handle, id.catalogId, STENCIL, id.key.value)

    /** Stencil [id] as the version the handle means holds it, or null. */
    fun get(handle: Handle, id: StencilId): Stencil? = handle
        .createQuery("SELECT $STENCIL_COLUMNS $STENCIL_FROM WHERE s.tenant_key = :t AND s.catalog_id = ${catalogAt("t", "c")} AND s.id = :k")
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("k", id.key.value)
        .mapTo<Stencil>()
        .map { it.asRead(objectMapper) }
        .findOne()
        .orElse(null)

    /** What a stored content says, as stored: its own-catalog references relative. */
    fun fieldsOf(payload: JsonNode): StencilFields {
        val resource = StoredContent.resourceOf(payload)
        return StencilFields(
            name = resource.path("name").asString(),
            description = resource.get("description")?.takeUnless { it.isNull }?.asString(),
            tags = resource.path("tags").toList().map { it.asString() },
            content = objectMapper.treeToValue(resource.path("content"), TemplateDocument::class.java),
            parameterSchema = resource.get("parameterSchema")?.takeUnless { it.isNull },
            readsTemplateData = resource.path("readsTemplateData").asBoolean(false),
        )
    }

    companion object {
        /** The resource type, and content kind, of a stencil. */
        const val STENCIL = "stencil"
    }
}
