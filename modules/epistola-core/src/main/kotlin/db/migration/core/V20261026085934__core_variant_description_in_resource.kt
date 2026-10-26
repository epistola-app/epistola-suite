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

/**
 * A variant's description moves into its released form (contract `2.0.0-alpha.2`,
 * `VariantEntry.description`). Until then a variant's content kept it beside the resource,
 * `{ "resource": { title, templateModel, metadata }, "description": … }`; now it is
 * `{ "resource": { title, templateModel, metadata, description } }`, so a release cut from it
 * carries and fingerprints it.
 *
 * Only draft variant rows move. A release holding a content of the old shape was fingerprinted
 * without the description and stays as it is; its row keeps the description it showed.
 *
 * Each moved content is stored under its new hash with the references and binaries the old one had
 * (the description names nothing), the row names it, and a ready mark that still matched the old
 * digest (`template_variant_digest` of the template's content and the variant's) is moved to the
 * new one, so a ready variant stays ready.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. The content hash is SHA-256 of the JSON
 * with every object's keys sorted, at any depth, as the suite stores content.
 */
@Suppress("ClassName")
class V20261026085934__core_variant_description_in_resource : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        val rows = connection.prepareStatement(
            """
            SELECT p.tenant_key, p.catalog_id::text, p.version_id::text, p.parent_id::text, p.resource_key,
                   p.content_hash AS variant_hash, t.content_hash AS template_hash, c.payload::text
            FROM release_resources p
            JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
            JOIN release_resources t ON t.tenant_key = p.tenant_key AND t.catalog_id = p.catalog_id AND t.version_id = p.version_id
                AND t.resource_type = 'template' AND t.resource_id = p.parent_id
            WHERE p.resource_type = 'variant' AND p.version_id IS NOT NULL AND c.payload -> 'description' IS NOT NULL
            """,
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                    }
                }
            }
        }
        for (row in rows) {
            val moved = moved(mapper.readTree(row.payload) as ObjectNode)
            val canonical = canonical(moved)
            val hash = contentHash(canonical)
            store(connection, row, hash, canonical)
            connection.prepareStatement(
                """
                UPDATE resource_ready_marks SET digest = template_variant_digest(?, ?)
                WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid AND part = ?
                  AND digest = template_variant_digest(?, ?)
                """,
            ).use {
                it.setString(1, row.templateHash)
                it.setString(2, hash)
                it.setString(3, row.tenant)
                it.setString(4, row.versionId)
                it.setString(5, row.parentId)
                it.setString(6, row.key)
                it.setString(7, row.templateHash)
                it.setString(8, row.variantHash)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                UPDATE release_resources SET content_hash = ?
                WHERE tenant_key = ? AND catalog_id = ?::uuid AND version_id = ?::uuid AND resource_type = 'variant'
                  AND parent_id = ?::uuid AND resource_key = ?
                """,
            ).use {
                it.setString(1, hash)
                it.setString(2, row.tenant)
                it.setString(3, row.catalogId)
                it.setString(4, row.versionId)
                it.setString(5, row.parentId)
                it.setString(6, row.key)
                it.executeUpdate()
            }
        }
        if (rows.isNotEmpty()) log.info("Moved the description of {} draft variant(s) into its released form", rows.size)
    }

    private fun store(connection: Connection, row: Row, hash: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, 'variant', ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, row.tenant)
            it.setString(2, hash)
            it.setString(3, canonical)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key)
            SELECT tenant_key, ?, alias, resource_type, resource_key FROM resource_references WHERE tenant_key = ? AND content_hash = ?
            ON CONFLICT DO NOTHING
            """,
        ).use {
            it.setString(1, hash)
            it.setString(2, row.tenant)
            it.setString(3, row.variantHash)
            it.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO resource_binaries (tenant_key, content_hash, binary_hash)
            SELECT tenant_key, ?, binary_hash FROM resource_binaries WHERE tenant_key = ? AND content_hash = ?
            ON CONFLICT DO NOTHING
            """,
        ).use {
            it.setString(1, hash)
            it.setString(2, row.tenant)
            it.setString(3, row.variantHash)
            it.executeUpdate()
        }
    }

    /** One draft variant row whose content keeps its description beside the resource. */
    private data class Row(
        val tenant: String,
        val catalogId: String,
        val versionId: String,
        val parentId: String,
        val key: String,
        val variantHash: String,
        val templateHash: String,
        val payload: String,
    )

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 1195572391

        private val mapper = JsonMapper.builder().build()

        /** [content] with its description inside the resource; a blank one is dropped. */
        fun moved(content: ObjectNode): ObjectNode {
            val description = content.get("description")?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
            val copy = content.deepCopy()
            copy.remove("description")
            val resource = copy.get("resource") as? ObjectNode ?: copy.putObject("resource")
            if (description != null && !resource.has("description")) resource.put("description", description)
            return copy
        }

        /** The JSON the content hash is taken over: every object's keys sorted, at any depth. */
        fun canonical(payload: JsonNode): String = mapper.writeValueAsString(sortKeys(payload))

        /** SHA-256 of [canonical], lowercase hex. */
        fun contentHash(canonical: String): String = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun sortKeys(node: JsonNode): JsonNode = when (node) {
            is ObjectNode -> mapper.createObjectNode().also { sorted ->
                node.propertyNames().sorted().forEach { name -> sorted.set(name, sortKeys(node.get(name))) }
            }

            is ArrayNode -> mapper.createArrayNode().also { sorted -> node.forEach { sorted.add(sortKeys(it)) } }

            else -> node
        }
    }
}
