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
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.StoredContent
import app.epistola.suite.catalog.revisions.TemplateContents
import app.epistola.suite.catalog.revisions.VariantRows
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.assignDataExampleKeys
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * One variant of a stored template: its place in the template (key, attributes, whether it is the
 * default) and what its own content holds (title, description, model).
 *
 * [model] is the variant's model in its stored form when it is to be written; null keeps the model
 * the variant's content already holds.
 */
data class StoredVariant(
    val key: String,
    val title: String,
    val description: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false,
    val model: JsonNode? = null,
    /** The content the variant is stored as now; null for a variant not stored yet. */
    val contentHash: String? = null,
)

/**
 * A template as its draft stores it (ADR 0033, step 7b): the template's content, and one per
 * variant ([TemplateContents]). Own-catalog references are relative throughout, as a
 * release holds them: a theme of the template's own catalog is named without [themeCatalogKey], a
 * model's references without theirs.
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
    /**
     * Producer-specific data by reverse-DNS namespace (contract `CatalogResource.metadata`), kept as
     * received and never fingerprinted; what a producer said of one variant sits under
     * `{"<namespace>": {"variants": {"<variant>": …}}}`.
     */
    val metadata: Map<String, Map<String, Any?>>? = null,
) {
    val defaultVariant: StoredVariant get() = variants.firstOrNull { it.isDefault } ?: variants.first()

    fun variant(key: String): StoredVariant? = variants.firstOrNull { it.key == key }

    /** This template with [key]'s variant changed by [change]. */
    fun withVariant(key: String, change: (StoredVariant) -> StoredVariant): StoredTemplate = copy(variants = variants.map { if (it.key == key) change(it) else it })
}

/** A [StoredTemplate] as a draft holds it, with its row's revision and its content's hash. */
data class LoadedTemplate(
    val template: StoredTemplate,
    val revision: Long,
    val resourceId: UUID,
    val contentHash: String,
    val updatedAt: java.time.OffsetDateTime,
)

/**
 * Writes the templates of a draft (ADR 0033, step 7b): the template's content on its draft row and
 * each variant's on a row of its own ([VariantRows]), as
 * [TemplateContents] shapes them, so a release cut from the draft stores exactly the same. A write
 * stores what changed only: saving a variant writes its one content, under its own revision, and
 * leaves the template's row and the other variants alone. Read back through `current_templates` and
 * `current_template_variants`.
 */
@Component
class TemplateStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
) {

    /** [json], a model as `TemplateDocumentPreparation` prepared it, as a variant stores it. */
    fun storedModel(json: String): JsonNode = objectMapper.readTree(json)

    /** [model] of a template of [catalogKey] in its released form, as a variant stores it. */
    fun storedModel(model: TemplateDocument, catalogKey: String): JsonNode = TemplateModels.stored(model, catalogKey, objectMapper)

    /** The model [variant] of a template of [tenantKey] holds, as stored: own-catalog references relative. */
    fun model(handle: Handle, tenantKey: TenantKey, variant: StoredVariant): TemplateDocument? {
        variant.model?.let { return objectMapper.treeToValue(it, TemplateDocument::class.java) }
        val hash = variant.contentHash ?: return null
        return handle.createQuery("SELECT payload -> 'resource' -> 'templateModel' FROM resource_contents WHERE tenant_key = :t AND content_hash = :h")
            .bind("t", tenantKey)
            .bind("h", hash)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
            ?.takeUnless { it == "null" }
            ?.let { objectMapper.readValue(it, TemplateDocument::class.java) }
    }

    /** The wire form of [template] with [models] by variant key: what [TemplateContents] stores and assembles. */
    private fun resourceOf(template: StoredTemplate, models: Map<String, TemplateDocument>): TemplateResource {
        val default = template.defaultVariant
        return TemplateResource(
            key = template.key,
            name = template.name,
            themeKey = template.themeKey,
            themeCatalogKey = template.themeCatalogKey.takeIf { template.themeKey != null },
            pdfaEnabled = template.pdfaEnabled,
            dataModel = template.dataModel,
            dataExamples = template.dataExamples,
            templateModel = models[default.key] ?: PLACEHOLDER,
            variants = template.variants.map { variant ->
                VariantEntry(
                    key = variant.key,
                    name = variant.title,
                    attributes = variant.attributes,
                    templateModel = if (variant.key == default.key) null else models[variant.key] ?: PLACEHOLDER,
                    isDefault = variant.isDefault,
                    description = variant.description?.takeIf { it.isNotBlank() },
                )
            },
            description = template.description,
            metadata = template.metadata?.takeIf { it.isNotEmpty() },
        )
    }

    /** [template]'s own content, without the variants' (TemplateContents.head). */
    fun headPayload(template: StoredTemplate): ObjectNode = TemplateContents.head(resourceOf(template, emptyMap()), objectMapper)

    /** [variant]'s content, its model [model] (TemplateContents.part). */
    private fun partPayload(template: StoredTemplate, variant: StoredVariant, model: TemplateDocument): ObjectNode {
        val entry = resourceOf(template, emptyMap()).variants.first { it.key == variant.key }
        return TemplateContents.part(entry, model, objectMapper)
    }

    /**
     * Adds [template] to the targeted draft of [catalog], every variant with its model; one already
     * there is a unique violation. Its identity.
     */
    fun create(handle: Handle, catalog: CatalogId, template: StoredTemplate, actor: UUID?): UUID {
        val head = contents.store(handle, catalog.tenantKey, TemplateStore.TEMPLATE, headPayload(template))
        val id = DraftResources.insert(handle, catalog, TEMPLATE, template.key, head, template.name, template.description, actor)
        template.variants.forEach { variant ->
            val model = checkNotNull(model(handle, catalog.tenantKey, variant)) { "Variant ${variant.key} of ${template.key} has no model" }
            val hash = contents.store(handle, catalog.tenantKey, TemplateContents.VARIANT_KIND, partPayload(template, variant, model))
            VariantRows.insert(handle, catalog, id, variant.key, hash, variant.title, variant.description, actor)
        }
        return id
    }

    /**
     * Saves [after] over [before] in the targeted draft of [catalog]: the template's content when it
     * changed, and each variant that changed, was added or went. Null when the template's row moved on
     * from [expectedRevision]; else its row after the write.
     */
    fun save(handle: Handle, catalog: CatalogId, before: LoadedTemplate, after: StoredTemplate, actor: UUID?, expectedRevision: Long? = null): DraftResources.Updated? = write(handle, catalog, before, after, actor, expectedRevision)?.first

    /** What the template's content says of it: everything but its variants' own fields. */
    private fun headOf(template: StoredTemplate): StoredTemplate = template.copy(
        variants = template.variants.map { StoredVariant(key = it.key, title = "", attributes = it.attributes, isDefault = it.isDefault) },
    )

    /** [save], with the revision of each variant row it wrote, by key. */
    private fun write(handle: Handle, catalog: CatalogId, before: LoadedTemplate, after: StoredTemplate, actor: UUID?, expectedRevision: Long?): Pair<DraftResources.Updated, Map<String, Long>>? {
        val tenantKey = catalog.tenantKey
        // The template's content is written only when what it says changed: saving a variant leaves it alone.
        val headChanged = headOf(after) != headOf(before.template)
        val updated = if (headChanged || after.name != before.template.name || after.description != before.template.description || expectedRevision != null) {
            val head = if (headChanged) contents.store(handle, tenantKey, TEMPLATE, headPayload(after)) else before.contentHash
            DraftResources.update(handle, catalog, TEMPLATE, after.key, head, after.name, after.description, actor, expectedRevision) ?: return null
        } else {
            // The template's own row is untouched: only variants changed.
            DraftResources.Updated(before.revision, before.updatedAt)
        }
        val previous = before.template.variants.associateBy { it.key }
        val written = mutableMapOf<String, Long>()
        for (variant in after.variants) {
            val old = previous[variant.key]
            if (old != null && variant.model == null && old.copy(attributes = variant.attributes, isDefault = variant.isDefault) == variant) continue
            val model = checkNotNull(model(handle, tenantKey, variant.copy(contentHash = variant.contentHash ?: old?.contentHash))) { "Variant ${variant.key} of ${after.key} has no model" }
            val hash = contents.store(handle, tenantKey, TemplateContents.VARIANT_KIND, partPayload(after, variant, model))
            if (old == null) {
                VariantRows.insert(handle, catalog, before.resourceId, variant.key, hash, variant.title, variant.description, actor)
            } else if (hash != old.contentHash) {
                VariantRows.update(handle, catalog, before.resourceId, variant.key, hash, variant.title, variant.description, actor)?.let { written[variant.key] = it.revision }
            }
        }
        (previous.keys - after.variants.map { it.key }.toSet()).forEach { VariantRows.delete(handle, catalog, before.resourceId, it) }
        return updated to written
    }

    /**
     * Changes template [id] of the targeted draft by [change], under its row's lock: refused as stale
     * when its row moved on from [expectedRevision] ([Revisions.refuse]); null when the draft has no
     * such template or [change] declines. The template as saved, and its row after the write.
     */
    fun modify(handle: Handle, id: TemplateId, actor: UUID?, expectedRevision: Long?, label: String, change: (StoredTemplate) -> StoredTemplate?): Pair<StoredTemplate, DraftResources.Updated>? {
        val revision = DraftResources.lockRevision(handle, id.catalogId, TEMPLATE, id.key.value) ?: return null
        Revisions.refuse(label, expectedRevision, revision)
        val current = get(handle, id) ?: return null
        val changed = change(current.template) ?: return null
        val updated = checkNotNull(save(handle, id.catalogId, current, changed, actor)) { "Template ${id.key.value} was locked" }
        return changed to updated
    }

    /**
     * Changes variant [id] by [change], under its own lock and revision: refused as stale when it moved
     * on from [expectedRevision]; null when there is no such variant or [change] declines. Another
     * variant's revision is not this one's, so saving one does not refuse a save of another. A change
     * of what the template's content says of its variants (attributes, the default) takes the
     * template's lock too ([headChange]); a change of the variant's own content alone does not. The
     * template as saved, and the variant's revision after the write.
     */
    fun modifyVariant(
        handle: Handle,
        id: VariantId,
        actor: UUID?,
        expectedRevision: Long?,
        label: String,
        headChange: Boolean = true,
        change: (StoredTemplate) -> StoredTemplate?,
    ): Pair<StoredTemplate, Long>? {
        if (headChange) DraftResources.lockRevision(handle, id.catalogId, TEMPLATE, id.templateKey.value) ?: return null
        val revision = VariantRows.lockRevision(handle, id.catalogId, id.templateKey.value, id.key.value) ?: return null
        Revisions.refuse(label, expectedRevision, revision)
        val current = get(handle, id.templateId) ?: return null
        val changed = change(current.template) ?: return null
        check(headChange || headOf(changed) == headOf(current.template)) { "A change of variant ${id.key.value}'s own content changed its template's" }
        val (_, written) = checkNotNull(write(handle, id.catalogId, current, changed, actor, null)) { "Template ${id.templateKey.value} was locked" }
        return changed to (written[id.key.value] ?: revision)
    }

    fun delete(handle: Handle, id: TemplateId): Boolean = DraftResources.delete(handle, id.catalogId, TEMPLATE, id.key.value)

    /** Template [id] as the version the handle means holds it, or null. */
    fun get(handle: Handle, id: TemplateId): LoadedTemplate? {
        val row = handle
            .createQuery(
                "SELECT payload::text AS payload, revision, resource_id, content_hash, updated_at, version_id, version FROM current_templates WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k",
            )
            .bind("t", id.tenantKey)
            .bindCatalog("c", id.catalogId)
            .bind("k", id.key.value)
            .map { rs, _ ->
                listOf(rs.getString("payload"), rs.getLong("revision").toString(), rs.getString("resource_id"), rs.getString("content_hash"), rs.getString("version_id"), rs.getString("version")) to
                    rs.getObject("updated_at", java.time.OffsetDateTime::class.java)
            }
            .findOne()
            .orElse(null) ?: return null
        val (columns, updatedAt) = row
        val resourceId = UUID.fromString(columns[2])
        // Its variants' rows, read by its identity (VariantRows); a release retained before variants had
        // rows holds them in the template's content, which the view reads.
        val variants = VariantRows.of(handle, id.tenantKey, resourceId, columns[4]?.let(UUID::fromString), columns[5])
            .mapValues { (_, row) -> listOf(row.title, row.description, row.contentHash) }
            .ifEmpty { legacyVariants(handle, id, columns[2]) }
        return LoadedTemplate(of(objectMapper.readTree(columns[0]), variants), columns[1].toLong(), resourceId, columns[3], updatedAt)
    }

    /** The variants' fields of template [id] as `current_template_variants` reads them from content retained before variants had rows. */
    private fun legacyVariants(handle: Handle, id: TemplateId, resourceId: String): Map<String, List<String?>> = handle
        .createQuery(
            """
            SELECT tv.id, tv.title, tv.description, tv.model_hash
            FROM current_template_variants tv
            WHERE tv.tenant_key = :t AND tv.catalog_id = ${catalogAt("t", "c")} AND tv.template_resource_id = CAST(:id AS UUID)
            """,
        )
        .bind("t", id.tenantKey)
        .bindCatalog("c", id.catalogId)
        .bind("id", resourceId)
        .map { rs, _ -> rs.getString("id") to listOf(rs.getString("title"), rs.getString("description"), rs.getString("model_hash")) }
        .list()
        .toMap()

    /** What a template's content and its variants' fields ([variants]: title, description, content hash, by key) say. */
    private fun of(payload: JsonNode, variants: Map<String, List<String?>>): StoredTemplate {
        val resource = StoredContent.resourceOf(payload)
        return StoredTemplate(
            key = resource.path("key").asString(),
            name = resource.path("name").asString(),
            description = resource.text("description"),
            themeKey = resource.text("themeKey"),
            themeCatalogKey = resource.text("themeCatalogKey"),
            pdfaEnabled = resource.path("pdfaEnabled").asBoolean(true),
            dataModel = resource.get("dataModel")?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, MAP) },
            dataExamples = resource.get("dataExamples")?.takeUnless { it.isNull }?.toList()?.map { objectMapper.treeToValue(it, DataExampleEntry::class.java) }.orEmpty(),
            variants = resource.path("variants").toList().map { slot ->
                val key = slot.path("key").asString()
                val part = variants[key]
                StoredVariant(
                    key = key,
                    title = part?.get(0) ?: key,
                    description = part?.get(1),
                    attributes = slot.get("attributes")?.takeUnless { it.isNull }?.let { objectMapper.treeToValue(it, STRINGS) }.orEmpty(),
                    isDefault = slot.path("isDefault").asBoolean(false),
                    contentHash = part?.get(2),
                )
            },
            metadata = resource.get("metadata")?.takeIf { it.isObject && !it.isEmpty }?.let { objectMapper.readValue(it.toString(), METADATA) },
        )
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotEmpty() }

    companion object {
        /** The resource type, and content kind, of a template. */
        const val TEMPLATE = "template"

        /** Stands in for a model while the wire form is serialized; [TemplateContents.head] leaves models out. */
        private val PLACEHOLDER = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = emptyList())),
            slots = emptyMap(),
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
