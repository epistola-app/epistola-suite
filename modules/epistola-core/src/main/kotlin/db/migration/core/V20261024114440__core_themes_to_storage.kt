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
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.Connection

/**
 * Every draft's themes move to JSON storage (ADR 0033, step 1): each `themes` row becomes its
 * content in `resource_contents`, the theme's wire form, and a draft row in `release_resources`
 * naming it, with the references the content holds in `resource_references`. The next migration
 * drops the table.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. What it knows is written into it and never
 * changes: how the theme columns of this time map to the theme's wire form of this time
 * (`ThemeResource` of contract 2.0), the content hash (SHA-256 of the JSON with every object's
 * keys sorted, as `ResourceContentStore` takes it), and where a theme's content names another
 * resource (`ResourceReferenceSites` of this time). `ThemeStorageConversionTest` proves that the
 * suite stores every bundled and demo theme exactly as this converts it, both ways.
 *
 * A row keeps its identity, its revision, and who created and last changed it, and when.
 */
@Suppress("ClassName")
class V20261024114440__core_themes_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        var converted = 0
        connection.prepareStatement(
            """
            SELECT tenant_key, catalog_id::text, version_id::text, resource_id::text, id, name, description,
                   document_styles::text, page_settings::text, block_style_presets::text, spacing_unit,
                   revision, created_at, updated_at, created_by::text, updated_by::text
            FROM themes
            ORDER BY tenant_key, version_id, id
            """,
        ).use { select ->
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val tenant = rows.getString("tenant_key")
                    val payload = payload(
                        key = rows.getString("id"),
                        name = rows.getString("name"),
                        description = rows.getString("description"),
                        documentStyles = rows.getString("document_styles"),
                        pageSettings = rows.getString("page_settings"),
                        blockStylePresets = rows.getString("block_style_presets"),
                        spacingUnit = rows.getBigDecimal("spacing_unit"),
                    )
                    val canonical = canonical(payload)
                    val hash = contentHash(canonical)
                    storeContent(connection, tenant, hash, canonical)
                    references(payload).forEach { storeReference(connection, tenant, hash, it) }
                    connection.prepareStatement(
                        """
                        INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id,
                            content_hash, name, description, revision, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?::uuid, ?::uuid, 'theme', ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid)
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
        log.info("Moved {} theme rows to JSON storage", converted)
    }

    private fun storeContent(connection: Connection, tenant: String, hash: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, 'theme', ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, canonical)
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
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = -258835513

        private val mapper = JsonMapper.builder().build()

        /**
         * The theme's wire form (`ThemeResource` of contract 2.0) from its columns: every field
         * present, null where the column is; the spacing unit a JSON float, as the protocol's
         * `Float` writes it.
         */
        fun payload(
            key: String,
            name: String,
            description: String?,
            documentStyles: String?,
            pageSettings: String?,
            blockStylePresets: String?,
            spacingUnit: BigDecimal?,
        ): ObjectNode = mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("description", description)
            set("documentStyles", documentStyles?.let(mapper::readTree) ?: mapper.createObjectNode())
            set("pageSettings", pageSettings?.let(mapper::readTree) ?: nullNode())
            set("blockStylePresets", blockStylePresets?.let(mapper::readTree) ?: nullNode())
            if (spacingUnit == null) putNull("spacingUnit") else put("spacingUnit", spacingUnit.toFloat())
            put("type", "theme")
        }

        /** The JSON the content hash is taken over: every object's keys sorted, at any depth. */
        fun canonical(payload: JsonNode): String = mapper.writeValueAsString(sortKeys(payload))

        /** SHA-256 of [canonical], lowercase hex. */
        fun contentHash(canonical: String): String = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        /**
         * Where a content names another resource, as `ResourceReferenceSites` of this time finds it: a
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

        private fun collect(node: JsonNode, into: MutableSet<Reference>) {
            if (node is ObjectNode) {
                val nodeType = node.get("type")?.takeIf { it.isString }?.asString()
                for (site in sites) {
                    val holder = if (site.nodeType != null) node.get("props").takeIf { nodeType == site.nodeType } else node.get(site.container!!)
                    if (holder !is ObjectNode) continue
                    val key = holder.text(site.keyField) ?: continue
                    into += Reference(holder.text("catalogKey"), site.type, key)
                }
                node.properties().forEach { (_, child) -> collect(child, into) }
            } else if (node is ArrayNode) {
                node.forEach { collect(it, into) }
            }
        }

        private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }

        private fun sortKeys(node: JsonNode): JsonNode = when (node) {
            is ObjectNode -> mapper.createObjectNode().also { sorted ->
                node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
            }

            is ArrayNode -> mapper.createArrayNode().also { sorted -> node.forEach { sorted.add(sortKeys(it)) } }

            else -> node
        }
    }
}
