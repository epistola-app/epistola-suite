// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.catalog.protocol.DataExampleEntry
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.REVISION_REF_FIELD
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.StoredContent
import app.epistola.suite.catalog.revisions.TEMPLATE_MODEL_KIND
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.assignDataExampleKeys
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * One variant of a stored template: what the wire form says of it, its description (kept beside the
 * resource until the contract carries it), and the content hash of its model.
 */
data class StoredVariant(
    val key: String,
    val title: String,
    val description: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
    val metadata: Map<String, Map<String, Any?>>? = null,
    /** The content its model is stored as (`templateModel`, ADR 0026 §3a). */
    val modelHash: String,
)

/**
 * A template as its draft stores it (ADR 0033, step 7b): its wire form with each variant's model by
 * content hash. Own-catalog references are relative throughout, as a release holds them: a theme of
 * the template's own catalog is named without [themeCatalogKey], a model's references without theirs.
 */
data class StoredTemplate(
    val key: String,
    val name: String,
    val description: String? = null,
    val themeKey: String? = null,
    /** The alias of [themeKey]'s catalog; null for the template's own catalog. */
    val themeCatalogKey: String? = null,
    val pdfaEnabled: Boolean = true,
    val dataModel: Map<String, Any?>? = null,
    val dataExamples: List<DataExampleEntry> = emptyList(),
    val variants: List<StoredVariant>,
) {
    val defaultVariant: StoredVariant get() = variants.firstOrNull { it.isDefault } ?: variants.first()

    fun variant(key: String): StoredVariant? = variants.firstOrNull { it.key == key }

    /** This template with [key]'s variant changed by [change]. */
    fun withVariant(key: String, change: (StoredVariant) -> StoredVariant): StoredTemplate = copy(variants = variants.map { if (it.key == key) change(it) else it })
}

/** A [StoredTemplate] as a draft holds it, with its row's revision. */
data class LoadedTemplate(val template: StoredTemplate, val revision: Long, val resourceId: UUID)

/**
 * Writes the templates of a draft (ADR 0033, step 7b): `{ "resource": <TemplateResource> }` in
 * `resource_contents`, each variant's model lifted into a content of its own (`templateModel`,
 * named by [REVISION_REF_FIELD]), and the draft's row naming it ([DraftResources]). A release cut
 * from the draft stores exactly this, so the two name one content. Read back through
 * `current_templates` and `current_template_variants`.
 *
 * Saving a model writes its content and the template's small one; every save keeps both until the
 * content collector takes what nothing names any more.
 */
@Component
class TemplateStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
) {

    /** Stores [json], a model as `TemplateDocumentPreparation` prepared it, and returns its content hash. */
    fun storeModel(handle: Handle, tenantKey: TenantKey, json: String): String = contents.store(handle, tenantKey, TEMPLATE_MODEL_KIND, objectMapper.readTree(json))

    /** Stores [model] of a template of [catalogKey] in its released form, and returns its content hash. */
    fun storeModel(handle: Handle, tenantKey: TenantKey, model: TemplateDocument, catalogKey: String): String = contents.store(handle, tenantKey, TEMPLATE_MODEL_KIND, TemplateModels.stored(model, catalogKey, objectMapper))

    /** The model stored as [hash], as stored: own-catalog references relative. */
    fun model(handle: Handle, tenantKey: TenantKey, hash: String): TemplateDocument? = handle
        .createQuery("SELECT payload::text FROM resource_contents WHERE tenant_key = :t AND content_hash = :h")
        .bind("t", tenantKey)
        .bind("h", hash)
        .mapTo(String::class.java)
        .findOne()
        .map { objectMapper.readValue(it, TemplateDocument::class.java) }
        .orElse(null)

    /**
     * The content [template] is stored as: its wire form (`TemplateResource`, serialized as a release
     * serializes it) with the default variant's model on the template and every model by its hash,
     * and beside it what the wire form does not carry yet (each variant's description).
     */
    fun payload(template: StoredTemplate): ObjectNode {
        val default = template.defaultVariant
        val resource = objectMapper.valueToTree<ObjectNode>(
            TemplateResource(
                key = template.key,
                name = template.name,
                themeKey = template.themeKey,
                themeCatalogKey = template.themeCatalogKey.takeIf { template.themeKey != null },
                pdfaEnabled = template.pdfaEnabled,
                dataModel = template.dataModel,
                dataExamples = template.dataExamples,
                templateModel = PLACEHOLDER,
                variants = template.variants.map { variant ->
                    VariantEntry(
                        key = variant.key,
                        title = variant.title,
                        attributes = variant.attributes,
                        templateModel = PLACEHOLDER.takeUnless { variant.key == default.key },
                        isDefault = variant.isDefault,
                        metadata = variant.metadata?.takeIf { it.isNotEmpty() },
                    )
                },
                description = template.description,
            ),
        )
        resource.set("templateModel", ref(default.modelHash))
        (resource.get("variants")).forEachIndexed { index, entry ->
            val variant = template.variants[index]
            if (variant.key != default.key) (entry as ObjectNode).set("templateModel", ref(variant.modelHash))
        }
        return objectMapper.createObjectNode().also { envelope ->
            envelope.set("resource", resource)
            settingsOf(template)?.let { envelope.set(VARIANT_DESCRIPTIONS, it.get(VARIANT_DESCRIPTIONS)) }
        }
    }

    /** What [template] keeps beside its released form, or null when nothing. */
    fun settingsOf(template: StoredTemplate): ObjectNode? {
        val described = template.variants.filter { !it.description.isNullOrBlank() }
        if (described.isEmpty()) return null
        return objectMapper.createObjectNode().also { settings ->
            settings.putObject(VARIANT_DESCRIPTIONS).also { map -> described.forEach { map.put(it.key, it.description) } }
        }
    }

    private fun ref(hash: String): ObjectNode = objectMapper.createObjectNode().put(REVISION_REF_FIELD, hash)

    /** [template]'s content, stored; its hash. */
    fun store(handle: Handle, tenantKey: TenantKey, template: StoredTemplate): String = contents.store(handle, tenantKey, TEMPLATE, payload(template))

    /** Adds [template] to the targeted draft of [catalog]; one already there is a unique violation. Its identity. */
    fun create(handle: Handle, catalog: CatalogId, template: StoredTemplate, actor: UUID?): UUID = DraftResources.insert(handle, catalog, TEMPLATE, template.key, store(handle, catalog.tenantKey, template), template.name, template.description, actor)

    /** Saves [template] in the targeted draft of [catalog]; null when it holds none, or it moved on from [expectedRevision]. */
    fun write(handle: Handle, catalog: CatalogId, template: StoredTemplate, actor: UUID?, expectedRevision: Long? = null): DraftResources.Updated? = DraftResources.update(
        handle,
        catalog,
        TEMPLATE,
        template.key,
        store(handle, catalog.tenantKey, template),
        template.name,
        template.description,
        actor,
        expectedRevision,
    )

    /**
     * Changes template [id] of the targeted draft by [change], under its row's lock: refused as stale
     * when it moved on from [expectedRevision] ([Revisions.refuse]); null when the draft has no such
     * template or [change] declines. The template as saved, and its row after the write.
     */
    fun modify(handle: Handle, id: TemplateId, actor: UUID?, expectedRevision: Long?, label: String, change: (StoredTemplate) -> StoredTemplate?): Pair<StoredTemplate, DraftResources.Updated>? {
        val revision = DraftResources.lockRevision(handle, id.catalogId, TEMPLATE, id.key.value) ?: return null
        Revisions.refuse(label, expectedRevision, revision)
        val current = get(handle, id) ?: return null
        val changed = change(current.template) ?: return null
        val updated = checkNotNull(write(handle, id.catalogId, changed, actor)) { "Template ${id.key.value} was locked" }
        return changed to updated
    }

    fun delete(handle: Handle, id: TemplateId): Boolean = DraftResources.delete(handle, id.catalogId, TEMPLATE, id.key.value)

    /**
     * Moves template [key] of [source] to [targetKey] of [target]. Its models and its theme are read as
     * they meant in [source] and stored in [target]'s form: what named the catalog it leaves still
     * does, and what names the catalog it joins becomes relative. False when there is none.
     */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean {
        val loaded = get(handle, TemplateId(app.epistola.suite.common.ids.TemplateKey.of(key), source)) ?: return false
        val template = loaded.template
        val variants = template.variants.map { variant ->
            val model = checkNotNull(model(handle, source.tenantKey, variant.modelHash)) { "Template $key names a model that is not stored" }
            val read = TemplateModels.read(model, source.key.value, objectMapper)
            variant.copy(modelHash = storeModel(handle, target.tenantKey, read, target.key.value))
        }
        val themeAlias = (template.themeCatalogKey ?: source.key.value).takeUnless { it == target.key.value }
        val moved = template.copy(key = targetKey, variants = variants, themeCatalogKey = themeAlias.takeIf { template.themeKey != null })
        return DraftResources.move(handle, source, TEMPLATE, key, target, targetKey, store(handle, target.tenantKey, moved))
    }

    /** Template [id] as the version the handle means holds it, or null. */
    fun get(handle: Handle, id: TemplateId): LoadedTemplate? = handle
        .createQuery(
            "SELECT payload::text AS payload, revision, resource_id FROM current_templates WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k",
        )
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("k", id.key.value)
        .map { rs, _ -> LoadedTemplate(of(objectMapper.readTree(rs.getString("payload"))), rs.getLong("revision"), rs.getObject("resource_id", UUID::class.java)) }
        .findOne()
        .orElse(null)

    /**
     * What a stored content says. A variant without a model of its own (a received release may leave
     * one out, ADR 0026) shares the default variant's.
     */
    fun of(payload: JsonNode): StoredTemplate {
        val resource = StoredContent.resourceOf(payload)
        val descriptions = payload.path(VARIANT_DESCRIPTIONS)
        val defaultModel = refOf(resource.path("templateModel"))
        val entries = resource.path("variants").toList()
        return StoredTemplate(
            key = resource.path("key").asString(),
            name = resource.path("name").asString(),
            description = resource.text("description"),
            themeKey = resource.text("themeKey"),
            themeCatalogKey = resource.text("themeCatalogKey"),
            pdfaEnabled = resource.path("pdfaEnabled").asBoolean(true),
            dataModel = resource.get("dataModel")?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, MAP) },
            dataExamples = resource.get("dataExamples")?.takeUnless { it.isNull }?.toList()?.map { objectMapper.treeToValue(it, DataExampleEntry::class.java) }.orEmpty(),
            variants = entries.map { entry ->
                val key = entry.path("key").asString()
                StoredVariant(
                    key = key,
                    title = entry.text("title") ?: key,
                    description = descriptions.text(key),
                    attributes = entry.get("attributes")?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, STRINGS) }.orEmpty(),
                    isDefault = entry.path("isDefault").asBoolean(false),
                    metadata = entry.get("metadata")?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, METADATA) },
                    modelHash = refOf(entry.path("templateModel")) ?: checkNotNull(defaultModel) { "Template ${resource.path("key").asString()} has no model" },
                )
            },
        )
    }

    /**
     * What every template of the targeted draft of [catalog] keeps beside its released form, keyed
     * `"template/<key>"`: what a release cut from it keeps too ([ResourceContentStore.retain]).
     */
    fun settings(handle: Handle, catalog: CatalogId): Map<String, ObjectNode> = handle
        .createQuery("SELECT id, payload::text AS payload FROM current_templates WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND payload -> '$VARIANT_DESCRIPTIONS' IS NOT NULL")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .map { rs, _ ->
            val payload = objectMapper.readTree(rs.getString("payload"))
            "$TEMPLATE/${rs.getString("id")}" to objectMapper.createObjectNode().also { it.set(VARIANT_DESCRIPTIONS, payload.get(VARIANT_DESCRIPTIONS)) }
        }
        .list()
        .toMap()

    private fun refOf(node: JsonNode): String? = node.get(REVISION_REF_FIELD)?.takeIf { it.isString }?.asString()

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }

    companion object {
        /** The resource type, and content kind, of a template. */
        const val TEMPLATE = "template"

        /**
         * Each variant's description by its key, beside the resource until the contract carries it
         * on the variant (`VariantEntry.description`, after contract `2.0.0-alpha.1`); then a code
         * migration moves it into the resource.
         */
        const val VARIANT_DESCRIPTIONS = "variantDescriptions"

        /** Stands in for a model while the wire form is serialized; every one is replaced by its hash. */
        private val PLACEHOLDER = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = emptyList())),
            slots = emptyMap<String, Slot>(),
        )

        private val MAP = object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {}
        private val STRINGS = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
        private val METADATA = object : tools.jackson.core.type.TypeReference<Map<String, Map<String, Any?>>>() {}

        /** [examples] as the wire form keeps them: each by its key (derived when it has none), without the id. */
        fun entriesOf(examples: List<DataExample>, objectMapper: ObjectMapper): List<DataExampleEntry> = assignDataExampleKeys(examples).map { example ->
            DataExampleEntry(name = example.name, data = objectMapper.convertValue(example.data, MAP), key = example.key)
        }

        /** [entries] as the suite reads them: each by its key (derived as a release derives one when it has none), its id its key. */
        fun examplesOf(entries: List<DataExampleEntry>, objectMapper: ObjectMapper): List<DataExample> = assignDataExampleKeys(
            entries.map { entry -> DataExample(id = entry.key ?: entry.name, name = entry.name, data = objectMapper.valueToTree(entry.data), key = entry.key) },
        ).map { it.copy(id = checkNotNull(it.key)) }
    }
}
