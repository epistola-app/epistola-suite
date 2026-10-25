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
 * Every draft's variant attributes move to JSON storage (ADR 0033, step 3): each
 * `variant_attribute_definitions` row becomes its content in `resource_contents`,
 * `{ "resource": <its wire form> }`, a draft row in `release_resources` naming it, and, for an
 * attribute bound to a code list, the reference in `resource_references`. The next migration drops
 * the table.
 *
 * A binding was an identity (`code_list_resource_id`); content holds what the wire form holds, the
 * code list by alias and key (ADR 0033: references are not foreign keys). The alias is the one the
 * attribute's draft declares for the list's catalog, its key where none does (this installation
 * declares every dependency under its key), and none for a list of the attribute's own catalog.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. What it knows is written into it and never
 * changes: how the attribute columns of this time map to the wire form of this time
 * (`AttributeResource` of contract 2.0: key, name, allowedValues, codeListBinding, type), and the
 * content hash (SHA-256 of the JSON with every object's keys sorted, as `ResourceContentStore`
 * takes it). `AttributeStorageConversion` proves the suite stores every bundled attribute exactly as
 * this converts it.
 */
@Suppress("ClassName")
class V20261025072712__core_attributes_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        var converted = 0
        connection.prepareStatement(
            """
            SELECT a.tenant_key, a.catalog_id::text, a.version_id::text, a.resource_id::text, a.id, a.display_name,
                   a.allowed_values::text, a.revision, a.created_at, a.updated_at,
                   bound.resource_key AS bound_key,
                   CASE WHEN bound.catalog_id = a.catalog_id THEN NULL
                        ELSE COALESCE(
                            (SELECT d.alias FROM catalog_dependencies d
                             WHERE d.tenant_key = a.tenant_key AND d.version_id = a.version_id
                               AND d.namespace = bound_catalog.namespace AND d.dependency_key = bound_catalog.catalog_key
                             ORDER BY d.position LIMIT 1),
                            bound_catalog.catalog_key)
                   END AS bound_alias
            FROM variant_attribute_definitions a
            LEFT JOIN catalog_resources bound ON bound.tenant_key = a.tenant_key AND bound.resource_id = a.code_list_resource_id
            LEFT JOIN catalogs bound_catalog ON bound_catalog.tenant_key = bound.tenant_key AND bound_catalog.catalog_id = bound.catalog_id
            ORDER BY a.tenant_key, a.version_id, a.id
            """,
        ).use { select ->
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val tenant = rows.getString("tenant_key")
                    val boundKey = rows.getString("bound_key")
                    val boundAlias = rows.getString("bound_alias")
                    val payload = payload(
                        key = rows.getString("id"),
                        name = rows.getString("display_name"),
                        allowedValues = rows.getString("allowed_values"),
                        bindingAlias = boundAlias,
                        bindingKey = boundKey,
                    )
                    val canonical = canonical(payload)
                    val hash = contentHash(canonical)
                    storeContent(connection, tenant, hash, canonical)
                    if (boundKey != null) storeReference(connection, tenant, hash, boundAlias, boundKey)
                    connection.prepareStatement(
                        """
                        INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id,
                            content_hash, name, revision, created_at, updated_at)
                        VALUES (?, ?::uuid, ?::uuid, 'attribute', ?, ?::uuid, ?, ?, ?, ?, ?)
                        """,
                    ).use { insert ->
                        insert.setString(1, tenant)
                        insert.setString(2, rows.getString("catalog_id"))
                        insert.setString(3, rows.getString("version_id"))
                        insert.setString(4, rows.getString("id"))
                        insert.setString(5, rows.getString("resource_id"))
                        insert.setString(6, hash)
                        insert.setString(7, rows.getString("display_name"))
                        insert.setLong(8, rows.getLong("revision"))
                        insert.setTimestamp(9, rows.getTimestamp("created_at"))
                        insert.setTimestamp(10, rows.getTimestamp("updated_at"))
                        insert.executeUpdate()
                    }
                    converted++
                }
            }
        }
        log.info("Moved {} variant attribute rows to JSON storage", converted)
    }

    private fun storeContent(connection: Connection, tenant: String, hash: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, 'attribute', ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, canonical)
            it.executeUpdate()
        }
    }

    private fun storeReference(connection: Connection, tenant: String, hash: String, alias: String?, key: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_references (tenant_key, content_hash, alias, resource_type, resource_key) VALUES (?, ?, ?, 'codeList', ?)
            ON CONFLICT DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, alias)
            it.setString(4, key)
            it.executeUpdate()
        }
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 1863491831

        private val mapper = JsonMapper.builder().build()

        /**
         * The content of an attribute from its columns: `{ "resource": <AttributeResource> }`, every
         * field present, `codeListBinding` null for an unbound attribute and `{ catalogKey, key }`
         * for a bound one, `catalogKey` null for a list of the attribute's own catalog.
         */
        fun payload(key: String, name: String, allowedValues: String?, bindingAlias: String?, bindingKey: String?): ObjectNode = mapper.createObjectNode().set(
            "resource",
            mapper.createObjectNode().apply {
                put("key", key)
                put("name", name)
                set("allowedValues", allowedValues?.let(mapper::readTree) ?: mapper.createArrayNode())
                if (bindingKey == null) {
                    putNull("codeListBinding")
                } else {
                    set("codeListBinding", mapper.createObjectNode().put("catalogKey", bindingAlias).put("key", bindingKey))
                }
                put("type", "attribute")
            },
        )

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
