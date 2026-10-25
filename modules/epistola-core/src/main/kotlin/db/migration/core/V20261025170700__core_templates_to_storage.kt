// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.sql.Connection
import java.text.Normalizer

/**
 * Every draft's templates move to JSON storage (ADR 0033, step 7b): each `document_templates` row
 * and its `template_variants` become the template's content in `resource_contents`,
 * `{ "resource": <the template's wire form> }`, each variant's model a content of its own
 * (`templateModel`, named by `revisionDigest`), a draft row in `release_resources` naming the
 * template's, and the references each content holds in `resource_references`. The next migration
 * drops the tables.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. What it knows is written into it and never
 * changes:
 * - the template's wire form of this time (`TemplateResource` and `VariantEntry` of contract
 *   `2.0.0-alpha.1`, serialized as the suite serializes them: every member present, `description`
 *   only when there is one, a variant's `metadata` only when it has some, the default variant's model
 *   on the template and none on its entry);
 * - a model in its released form (`CatalogContentBuilder.Outbound` of this time): a reference into
 *   the template's own catalog relative, and none with the `namespace` or `registry` an earlier draft
 *   of wire v8 allowed;
 * - a theme of the template's own catalog named without `themeCatalogKey`;
 * - each data example by its key, without the id the suite kept beside it; a key an example lacks is
 *   derived from its name as a release derives one ([dataExampleKey], `CatalogKeys` of this time,
 *   pinned by golden vectors in `TemplatesToStorageMigrationTest`);
 * - each variant's description, which the wire form of this time does not carry, beside the
 *   resource (`variantDescriptions`);
 * - the content hash (SHA-256 of the JSON with every object's keys sorted, as `ResourceContentStore`
 *   takes it) and where a content names another resource (`ResourceReferenceSites` of this time).
 *
 * `TemplateStorageConversion` proves that the suite stores every bundled and demo template exactly
 * as this converts it. A template keeps its identity, its revision, and who created and last
 * changed it, and when; its variants share its row's, as every other type's parts do.
 */
@Suppress("ClassName")
class V20261025170700__core_templates_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        var converted = 0
        connection.prepareStatement(
            """
            SELECT t.tenant_key, t.catalog_id::text, t.version_id::text, t.resource_id::text, t.id, t.name, t.description,
                   t.pdfa_enabled, t.data_model::text, t.data_examples::text, t.revision, t.created_at, t.updated_at,
                   t.created_by::text, t.updated_by::text, c.catalog_key::text AS catalog_key,
                   theme.resource_key AS theme_key, theme_catalog.catalog_key::text AS theme_catalog_key
            FROM document_templates t
            JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
            LEFT JOIN catalog_resources theme ON theme.tenant_key = t.tenant_key AND theme.resource_id = t.theme_resource_id
            LEFT JOIN catalogs theme_catalog ON theme_catalog.tenant_key = theme.tenant_key AND theme_catalog.catalog_id = theme.catalog_id
            ORDER BY t.tenant_key, t.version_id, t.id
            """,
        ).use { select ->
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val tenant = rows.getString("tenant_key")
                    val catalogKey = rows.getString("catalog_key")
                    val variants = variants(connection, tenant, rows.getString("version_id"), rows.getString("resource_id"))
                    if (variants.isEmpty()) {
                        log.warn("Template {} of tenant {} has no variant; it is kept with an empty one", rows.getString("id"), tenant)
                    }
                    val modelHashes = variants.associate { variant ->
                        val model = releasedModel(mapper.readTree(variant.model), catalogKey)
                        val canonical = canonical(model)
                        val hash = contentHash(canonical)
                        storeContent(connection, tenant, hash, "templateModel", canonical)
                        references(model).forEach { storeReference(connection, tenant, hash, it) }
                        variant.key to hash
                    }
                    val themeKey = rows.getString("theme_key")
                    val payload = payload(
                        key = rows.getString("id"),
                        name = rows.getString("name"),
                        description = rows.getString("description"),
                        themeKey = themeKey,
                        themeCatalogKey = rows.getString("theme_catalog_key")?.takeUnless { themeKey == null || it == catalogKey },
                        pdfaEnabled = rows.getBoolean("pdfa_enabled"),
                        dataModel = rows.getString("data_model"),
                        dataExamples = rows.getString("data_examples"),
                        variants = variants,
                        modelHashes = modelHashes,
                    )
                    val canonical = canonical(payload)
                    val hash = contentHash(canonical)
                    storeContent(connection, tenant, hash, "template", canonical)
                    if (themeKey != null) {
                        storeReference(connection, tenant, hash, Reference(payload.path("resource").text("themeCatalogKey"), "theme", themeKey))
                    }
                    connection.prepareStatement(
                        """
                        INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id,
                            content_hash, name, description, revision, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?::uuid, ?::uuid, 'template', ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid)
                        """,
                    ).use { insert ->
                        insert.setString(1, tenant)
                        insert.setString(2, rows.getString("catalog_id"))
                        insert.setString(3, rows.getString("version_id"))
                        insert.setString(4, rows.getString("id"))
                        insert.setString(5, rows.getString("resource_id"))
                        insert.setString(6, hash)
                        insert.setString(7, rows.getString("name"))
                        insert.setString(8, rows.getString("description"))
                        insert.setLong(9, rows.getLong("revision"))
                        insert.setTimestamp(10, rows.getTimestamp("created_at"))
                        insert.setTimestamp(11, rows.getTimestamp("updated_at"))
                        insert.setString(12, rows.getString("created_by"))
                        insert.setString(13, rows.getString("updated_by"))
                        insert.executeUpdate()
                    }
                    converted++
                }
            }
        }
        log.info("Moved {} templates to JSON storage", converted)
    }

    /** The template's variants, its default first, then as they were added. */
    private fun variants(connection: Connection, tenant: String, versionId: String, templateId: String): List<Variant> = connection.prepareStatement(
        """
        SELECT id, title, description, attributes::text, is_default, metadata::text, template_model::text
        FROM template_variants
        WHERE tenant_key = ? AND version_id = ?::uuid AND template_resource_id = ?::uuid
        ORDER BY is_default DESC, created_at, id
        """,
    ).use { st ->
        st.setString(1, tenant)
        st.setString(2, versionId)
        st.setString(3, templateId)
        st.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(Variant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6), rs.getString(7)))
                }
            }
        }
    }

    private fun storeContent(connection: Connection, tenant: String, hash: String, kind: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, kind)
            it.setString(4, canonical)
            it.executeUpdate()
        }
    }

    private fun storeReference(connection: Connection, tenant: String, hash: String, reference: Reference) {
        connection.prepareStatement(
            """
            INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, reference.alias)
            it.setString(4, reference.type)
            it.setString(5, reference.key)
            it.executeUpdate()
        }
    }

    /** One reference a content holds: the catalog alias it names (null for its own), type and key. */
    data class Reference(val alias: String?, val type: String, val key: String)

    companion object {
        /** A variant's columns: attributes, metadata and its model as JSONB text. */
        data class Variant(
            val key: String,
            val title: String,
            val description: String?,
            val attributes: String?,
            val isDefault: Boolean,
            val metadata: String?,
            val model: String,
        )

        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 839426554

        private val mapper = JsonMapper.builder().build()

        /** The template's content: `{ "resource": <TemplateResource>, "variantDescriptions": … }`. */
        fun payload(
            key: String,
            name: String,
            description: String?,
            themeKey: String?,
            themeCatalogKey: String?,
            pdfaEnabled: Boolean,
            dataModel: String?,
            dataExamples: String?,
            variants: List<Variant>,
            modelHashes: Map<String, String>,
        ): ObjectNode {
            val default = variants.firstOrNull { it.isDefault } ?: variants.firstOrNull()
            val resource = mapper.createObjectNode().apply {
                put("key", key)
                put("name", name)
                put("themeKey", themeKey)
                put("themeCatalogKey", themeCatalogKey)
                put("pdfaEnabled", pdfaEnabled)
                set("dataModel", dataModel?.let(mapper::readTree)?.takeUnless { it.isNull } ?: nullNode())
                set("dataExamples", examples(dataExamples))
                set("templateModel", default?.let { ref(modelHashes.getValue(it.key)) } ?: emptyModel())
                set(
                    "variants",
                    mapper.createArrayNode().also { entries ->
                        variants.forEach { variant ->
                            entries.add(
                                mapper.createObjectNode().apply {
                                    put("key", variant.key)
                                    put("title", variant.title)
                                    set("attributes", variant.attributes?.let(mapper::readTree)?.takeUnless { it.isNull } ?: mapper.createObjectNode())
                                    set("templateModel", if (variant.key == default?.key) nullNode() else ref(modelHashes.getValue(variant.key)))
                                    put("isDefault", variant.isDefault)
                                    // Left out when empty, as the wire form leaves it out.
                                    variant.metadata?.let(mapper::readTree)?.takeIf { it is ObjectNode && !it.isEmpty }?.let { set("metadata", it) }
                                },
                            )
                        }
                    },
                )
                if (description != null) put("description", description)
                put("type", "template")
            }
            return mapper.createObjectNode().also { envelope ->
                envelope.set("resource", resource)
                val described = variants.filter { !it.description.isNullOrBlank() }
                if (described.isNotEmpty()) {
                    envelope.putObject("variantDescriptions").also { map -> described.forEach { map.put(it.key, it.description) } }
                }
            }
        }

        private fun ref(hash: String): ObjectNode = mapper.createObjectNode().put("revisionDigest", hash)

        /** A template 1.x left without a variant renders an empty document. */
        private fun emptyModel(): ObjectNode = mapper.readTree(
            """{"modelVersion":1,"root":"root","nodes":{"root":{"id":"root","type":"root","slots":[],"styles":null,"stylePreset":null,"props":null}},"slots":{},"themeRef":{"type":"inherit"},"pageSettingsOverride":null,"documentStylesOverride":null}""",
        ) as ObjectNode

        /** The examples as the wire form keeps them: `name`, `data` and `key`, each with a key. */
        private fun examples(json: String?): ArrayNode {
            val examples = json?.let(mapper::readTree)?.takeIf { it is ArrayNode }?.toList().orEmpty()
            val taken = examples.mapNotNull { it.text("key") }.toMutableSet()
            return mapper.createArrayNode().also { out ->
                examples.forEach { example ->
                    val key = example.text("key") ?: dataExampleKey(example.text("name") ?: "Unnamed", taken).also { taken += it }
                    out.add(
                        mapper.createObjectNode().apply {
                            put("name", example.text("name") ?: "Unnamed")
                            set("data", example.get("data") ?: mapper.createObjectNode())
                            put("key", key)
                        },
                    )
                }
            }
        }

        /**
         * A data example's key from its name (`CatalogKeys.deriveDataExampleKey` of this time): the
         * name without accents, lowercased, every run of other characters one hyphen, trimmed, cut to
         * 50 characters without a trailing hyphen, padded with `0` to 3, `example` when nothing is
         * left; the first free suffix `-2`, `-3`, … when taken.
         */
        fun dataExampleKey(name: String, taken: Set<String>): String {
            val decomposed = Regex("\\p{M}+").replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
            val base = fit(Regex("[^a-z0-9]+").replace(decomposed.lowercase(), "-").trim('-'), 50).ifEmpty { "example" }.padEnd(3, '0')
            if (base !in taken) return base
            var suffix = 2
            while (true) {
                val candidate = fit(base, 50 - "-$suffix".length).ifEmpty { "example".take(50 - "-$suffix".length) } + "-$suffix"
                if (candidate !in taken) return candidate
                suffix++
            }
        }

        private fun fit(value: String, length: Int): String = if (value.length <= length) value else value.take(length).trimEnd('-')

        /** The JSON the content hash is taken over: every object's keys sorted, at any depth. */
        fun canonical(payload: JsonNode): String = mapper.writeValueAsString(sortKeys(payload))

        /** SHA-256 of [canonical], lowercase hex. */
        fun contentHash(canonical: String): String = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        /**
         * Where a model names another resource, as `ResourceReferenceSites` of this time finds it: a
         * `stencil` or `image` node's props, a `themeRef`, a `fontFamily`; each with the catalog alias
         * it names in `catalogKey`, when it names one.
         */
        fun references(node: JsonNode): Set<Reference> = buildSet { collect(node, this) }

        private val sites = listOf(
            Site("stencil", "stencilKey", nodeType = "stencil"),
            Site("image", "imageKey", nodeType = "image"),
            Site("theme", "themeKey", container = "themeRef"),
            Site("font", "key", container = "fontFamily"),
        )

        private data class Site(val type: String, val keyField: String, val nodeType: String? = null, val container: String? = null)

        private fun holderOf(node: ObjectNode, site: Site): ObjectNode? {
            val nodeType = node.get("type")?.takeIf { it.isString }?.asString()
            val holder = if (site.nodeType != null) node.get("props").takeIf { nodeType == site.nodeType } else node.get(site.container!!)
            return (holder as? ObjectNode)?.takeIf { it.text(site.keyField) != null }
        }

        private fun collect(node: JsonNode, into: MutableSet<Reference>) {
            if (node is ObjectNode) {
                for (site in sites) {
                    val holder = holderOf(node, site) ?: continue
                    into += Reference(holder.text("catalogKey"), site.type, holder.text(site.keyField)!!)
                }
                node.properties().forEach { (_, child) -> collect(child, into) }
            } else if (node is ArrayNode) {
                node.forEach { collect(it, into) }
            }
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

        /**
         * [model] of a template of [catalogKey] as a release holds it. A reference into its own catalog
         * loses its `catalogKey`: a `themeRef` (a typed member of the document) keeps it as null, as the
         * suite writes a document; a node's props and a style (free-form maps) leave it out. Images
         * are never qualified, so they are left alone.
         */
        fun releasedModel(model: JsonNode, catalogKey: String): JsonNode = model.deepCopy().also { relativize(it, catalogKey) }

        private fun relativize(node: JsonNode, catalogKey: String) {
            if (node is ObjectNode) {
                for (site in sites) {
                    val holder = holderOf(node, site) ?: continue
                    holder.remove("namespace")
                    holder.remove("registry")
                    if (site.type == "image" || holder.text("catalogKey") != catalogKey) continue
                    if (site.container == "themeRef") holder.putNull("catalogKey") else holder.remove("catalogKey")
                }
                node.properties().forEach { (_, child) -> relativize(child, catalogKey) }
            } else if (node is ArrayNode) {
                node.forEach { relativize(it, catalogKey) }
            }
        }

        private fun sortKeys(node: JsonNode): JsonNode = when (node) {
            is ObjectNode -> mapper.createObjectNode().also { sorted ->
                node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
            }

            is ArrayNode -> mapper.createArrayNode().also { sorted -> node.forEach { sorted.add(sortKeys(it)) } }

            else -> node
        }
    }
}
