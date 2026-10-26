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
 * A variant's producer metadata moves to its template (contract `2.0.0-alpha.3`: metadata is on every
 * resource, `CatalogResource.metadata`, and `VariantEntry.metadata` is gone). A draft variant's
 * content `{ "resource": { title, templateModel, metadata, description } }` loses its `metadata`, and
 * its template's content gains it as the contract's migrator folds an early wire-v8 variant's:
 * `{"<namespace>": V}` of variant `k` becomes `{"<namespace>": {"variants": {"k": V}}}`. A value the
 * template already holds is never overwritten; that variant's value is left where it was.
 *
 * Only draft rows move. A release holding the old shape keeps it; assembling its wire form folds the
 * same way (`TemplateContents.assemble`), and metadata counts in no fingerprint.
 *
 * Each changed content is stored under its new hash with the references and binaries the old one had
 * (metadata names nothing), the rows name them, and every variant's ready mark that still matched its
 * digest (`template_variant_digest` of the template's content and the variant's) is moved to the new
 * one, so a ready variant stays ready.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. The content hash is SHA-256 of the JSON with
 * every object's keys sorted, at any depth, as the suite stores content.
 */
@Suppress("ClassName")
class V20261026141633__core_template_metadata_on_template : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        val templates = connection.prepareStatement(
            """
            SELECT DISTINCT t.tenant_key, t.catalog_id::text, t.version_id::text, t.resource_id::text, t.content_hash, tc.payload::text
            FROM release_resources t
            JOIN resource_contents tc ON tc.tenant_key = t.tenant_key AND tc.content_hash = t.content_hash
            JOIN release_resources p ON p.tenant_key = t.tenant_key AND p.catalog_id = t.catalog_id AND p.version_id = t.version_id
                AND p.resource_type = 'variant' AND p.parent_id = t.resource_id
            JOIN resource_contents pc ON pc.tenant_key = p.tenant_key AND pc.content_hash = p.content_hash
            WHERE t.resource_type = 'template' AND t.version_id IS NOT NULL
              AND jsonb_typeof(pc.payload -> 'resource' -> 'metadata') = 'object'
            """,
        ).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Template(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                }
            }
        }
        var moved = 0
        for (template in templates) {
            val variants = variantsOf(connection, template)
            val metadata = variants.associate { it.key to (mapper.readTree(it.payload) as ObjectNode).path("resource").get("metadata") }
            val (head, folded) = foldedHead(mapper.readTree(template.payload) as ObjectNode, metadata)
            val headCanonical = canonical(head)
            val headHash = contentHash(headCanonical)
            store(connection, template.tenant, "template", headHash, headCanonical, template.contentHash)
            update(connection, template, "template", null, headHash)
            for (variant in variants) {
                val newHash = if (variant.key in folded) {
                    val part = stripped(mapper.readTree(variant.payload) as ObjectNode)
                    val partCanonical = canonical(part)
                    contentHash(partCanonical).also { hash ->
                        store(connection, template.tenant, "variant", hash, partCanonical, variant.contentHash)
                        update(connection, template, "variant", variant.key, hash)
                    }
                } else {
                    variant.contentHash
                }
                connection.prepareStatement(
                    """
                    UPDATE resource_ready_marks SET digest = template_variant_digest(?, ?)
                    WHERE tenant_key = ? AND version_id = ?::uuid AND resource_id = ?::uuid AND part = ?
                      AND digest = template_variant_digest(?, ?)
                    """,
                ).use {
                    it.setString(1, headHash)
                    it.setString(2, newHash)
                    it.setString(3, template.tenant)
                    it.setString(4, template.versionId)
                    it.setString(5, template.resourceId)
                    it.setString(6, variant.key)
                    it.setString(7, template.contentHash)
                    it.setString(8, variant.contentHash)
                    it.executeUpdate()
                }
            }
            moved += folded.size
        }
        if (moved > 0) log.info("Moved the metadata of {} draft variant(s) to their template", moved)
    }

    private fun variantsOf(connection: Connection, template: Template): List<Variant> = connection.prepareStatement(
        """
        SELECT p.resource_key, p.content_hash, c.payload::text
        FROM release_resources p
        JOIN resource_contents c ON c.tenant_key = p.tenant_key AND c.content_hash = p.content_hash
        WHERE p.tenant_key = ? AND p.catalog_id = ?::uuid AND p.version_id = ?::uuid
          AND p.resource_type = 'variant' AND p.parent_id = ?::uuid
        ORDER BY p.resource_key
        """,
    ).use { st ->
        st.setString(1, template.tenant)
        st.setString(2, template.catalogId)
        st.setString(3, template.versionId)
        st.setString(4, template.resourceId)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(Variant(rs.getString(1), rs.getString(2), rs.getString(3))) } }
    }

    private fun update(connection: Connection, template: Template, type: String, variantKey: String?, hash: String) {
        connection.prepareStatement(
            if (variantKey == null) {
                """
                UPDATE release_resources SET content_hash = ?
                WHERE tenant_key = ? AND catalog_id = ?::uuid AND version_id = ?::uuid AND resource_type = 'template' AND resource_id = ?::uuid
                """
            } else {
                """
                UPDATE release_resources SET content_hash = ?
                WHERE tenant_key = ? AND catalog_id = ?::uuid AND version_id = ?::uuid AND resource_type = '$type'
                  AND parent_id = ?::uuid AND resource_key = ?
                """
            },
        ).use {
            it.setString(1, hash)
            it.setString(2, template.tenant)
            it.setString(3, template.catalogId)
            it.setString(4, template.versionId)
            it.setString(5, template.resourceId)
            if (variantKey != null) it.setString(6, variantKey)
            it.executeUpdate()
        }
    }

    private fun store(connection: Connection, tenant: String, kind: String, hash: String, canonical: String, from: String) {
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
        for (table in listOf("resource_references (tenant_key, content_hash, alias, resource_type, resource_key)" to "alias, resource_type, resource_key", "resource_binaries (tenant_key, content_hash, binary_hash)" to "binary_hash")) {
            connection.prepareStatement(
                """
                INSERT INTO ${table.first}
                SELECT tenant_key, ?, ${table.second} FROM ${table.first.substringBefore(" (")} WHERE tenant_key = ? AND content_hash = ?
                ON CONFLICT DO NOTHING
                """,
            ).use {
                it.setString(1, hash)
                it.setString(2, tenant)
                it.setString(3, from)
                it.executeUpdate()
            }
        }
    }

    /** One draft template row with a variant whose content holds metadata. */
    private data class Template(val tenant: String, val catalogId: String, val versionId: String, val resourceId: String, val contentHash: String, val payload: String)

    /** One draft variant row of such a template. */
    private data class Variant(val key: String, val contentHash: String, val payload: String)

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = -1575953539

        private val mapper = JsonMapper.builder().build()

        /**
         * [head] with every variant's metadata ([byVariant], by variant key) folded into its resource's
         * `metadata`, and the keys of the variants whose metadata moved. A value the template already
         * holds, or a namespace it holds as something other than an object, keeps that variant's
         * metadata where it was.
         */
        fun foldedHead(head: ObjectNode, byVariant: Map<String, JsonNode?>): Pair<ObjectNode, Set<String>> {
            val copy = head.deepCopy()
            val resource = copy.get("resource") as? ObjectNode ?: copy.putObject("resource")
            val metadata = resource.get("metadata") as? ObjectNode ?: mapper.createObjectNode()
            val folded = mutableSetOf<String>()
            for ((variant, values) in byVariant.toSortedMap()) {
                if (values == null || !values.isObject || values.isEmpty) continue
                val fits = values.properties().all { (namespace, _) ->
                    val space = metadata.get(namespace)
                    space == null || (space.isObject && (space.get("variants") == null || (space.get("variants").isObject && !space.get("variants").has(variant))))
                }
                if (!fits) continue
                values.properties().forEach { (namespace, value) ->
                    val space = metadata.get(namespace) as? ObjectNode ?: metadata.putObject(namespace)
                    val variants = space.get("variants") as? ObjectNode ?: space.putObject("variants")
                    variants.set(variant, value)
                }
                folded += variant
            }
            if (folded.isNotEmpty()) resource.set("metadata", metadata)
            return copy to folded
        }

        /** [part] without its resource's `metadata`. */
        fun stripped(part: ObjectNode): ObjectNode = part.deepCopy().also { (it.get("resource") as? ObjectNode)?.remove("metadata") }

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
