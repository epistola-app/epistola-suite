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
 * Every draft's code lists move to JSON storage (ADR 0033, step 2): each `code_lists` row and its
 * `code_list_entries` become one content in `resource_contents` and a draft row in
 * `release_resources` naming it. The content is `{ "resource": <the wire form>, "source": {...} }`:
 * the code list as it is released (`CodeListResource` of contract 2.0: key, name, description, its
 * entries in their order, `type`), and beside it the source the suite refreshes it from, left out
 * for an INLINE list. A credential moves, still encrypted, to `resource_credentials`, and the source
 * names it by id. The last refresh moves to `code_list_refreshes`. The next migration drops the
 * tables.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. What it knows is written into it and never
 * changes: how the code list columns of this time map to that content, and the content hash (SHA-256
 * of the JSON with every object's keys sorted, as `ResourceContentStore` takes it). A code list's
 * content names no other resource (its entries are codes and labels), so it records no references.
 * `CodeListStorageConversion` proves the suite stores every bundled and demo code list exactly as
 * this converts it.
 *
 * A row keeps its identity, its revision, and when it was created and last changed.
 */
@Suppress("ClassName")
class V20261024174143__core_code_lists_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        var converted = 0
        connection.prepareStatement(
            """
            SELECT tenant_key, catalog_id::text, version_id::text, resource_id::text, slug, display_name, description,
                   source_type::text, source_url, auth_type::text, credential, last_refreshed_at, last_refresh_error,
                   revision, created_at, updated_at
            FROM code_lists
            ORDER BY tenant_key, version_id, slug
            """,
        ).use { select ->
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val tenant = rows.getString("tenant_key")
                    val versionId = rows.getString("version_id")
                    val resourceId = rows.getString("resource_id")
                    val credentialId = rows.getString("credential")?.let { encrypted -> storeCredential(connection, tenant, encrypted) }
                    val payload = payload(
                        key = rows.getString("slug"),
                        name = rows.getString("display_name"),
                        description = rows.getString("description"),
                        entries = entries(connection, tenant, versionId, resourceId),
                        sourceType = rows.getString("source_type"),
                        sourceUrl = rows.getString("source_url"),
                        authType = rows.getString("auth_type"),
                        credentialId = credentialId,
                    )
                    val canonical = canonical(payload)
                    val hash = contentHash(canonical)
                    storeContent(connection, tenant, hash, canonical)
                    connection.prepareStatement(
                        """
                        INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id,
                            content_hash, name, description, revision, created_at, updated_at)
                        VALUES (?, ?::uuid, ?::uuid, 'codeList', ?, ?::uuid, ?, ?, ?, ?, ?, ?)
                        """,
                    ).use { insert ->
                        insert.setString(1, tenant)
                        insert.setString(2, rows.getString("catalog_id"))
                        insert.setString(3, versionId)
                        insert.setString(4, rows.getString("slug"))
                        insert.setString(5, resourceId)
                        insert.setString(6, hash)
                        insert.setString(7, rows.getString("display_name"))
                        insert.setString(8, rows.getString("description"))
                        insert.setLong(9, rows.getLong("revision"))
                        insert.setTimestamp(10, rows.getTimestamp("created_at"))
                        insert.setTimestamp(11, rows.getTimestamp("updated_at"))
                        insert.executeUpdate()
                    }
                    if (rows.getTimestamp("last_refreshed_at") != null || rows.getString("last_refresh_error") != null) {
                        connection.prepareStatement(
                            """
                            INSERT INTO code_list_refreshes (tenant_key, version_id, resource_id, last_refreshed_at, last_refresh_error)
                            VALUES (?, ?::uuid, ?::uuid, ?, ?)
                            """,
                        ).use { insert ->
                            insert.setString(1, tenant)
                            insert.setString(2, versionId)
                            insert.setString(3, resourceId)
                            insert.setTimestamp(4, rows.getTimestamp("last_refreshed_at"))
                            insert.setString(5, rows.getString("last_refresh_error"))
                            insert.executeUpdate()
                        }
                    }
                    converted++
                }
            }
        }
        log.info("Moved {} code list rows to JSON storage", converted)
    }

    /** A code list's entries, in the order the suite lists them: by sort order, then code. */
    private fun entries(connection: Connection, tenant: String, versionId: String, resourceId: String): List<Entry> = connection.prepareStatement(
        """
        SELECT code, label, sort_order, hidden FROM code_list_entries
        WHERE tenant_key = ? AND version_id = ?::uuid AND code_list_resource_id = ?::uuid
        ORDER BY sort_order, code
        """,
    ).use { select ->
        select.setString(1, tenant)
        select.setString(2, versionId)
        select.setString(3, resourceId)
        select.executeQuery().use { rows ->
            buildList { while (rows.next()) add(Entry(rows.getString("code"), rows.getString("label"), rows.getInt("sort_order"), rows.getBoolean("hidden"))) }
        }
    }

    /** Moves an encrypted credential as it is (the cipher binds only its key id) and returns its new id. */
    private fun storeCredential(connection: Connection, tenant: String, encrypted: String): String = connection.prepareStatement("INSERT INTO resource_credentials (tenant_key, credential_id, credential) VALUES (?, epistola_uuidv7(), ?) RETURNING credential_id::text").use {
        it.setString(1, tenant)
        it.setString(2, encrypted)
        it.executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun storeContent(connection: Connection, tenant: String, hash: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, 'codeList', ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, canonical)
            it.executeUpdate()
        }
    }

    /** One entry of a code list, as its columns hold it. */
    data class Entry(val code: String, val label: String, val sortOrder: Int, val hidden: Boolean)

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 315015212

        private val mapper = JsonMapper.builder().build()

        /**
         * The content of a code list from its columns: `resource`, its wire form (`CodeListResource`
         * of contract 2.0: every field present, `description` null where the column is), and for a
         * list with a source, `source` beside it (`type`, `url`, `auth`, `credential` by id or null).
         */
        fun payload(
            key: String,
            name: String,
            description: String?,
            entries: List<Entry>,
            sourceType: String,
            sourceUrl: String?,
            authType: String,
            credentialId: String?,
        ): ObjectNode = mapper.createObjectNode().apply {
            set(
                "resource",
                mapper.createObjectNode().apply {
                    put("key", key)
                    put("name", name)
                    put("description", description)
                    set(
                        "entries",
                        mapper.createArrayNode().apply {
                            entries.forEach { entry ->
                                add(
                                    mapper.createObjectNode()
                                        .put("code", entry.code)
                                        .put("label", entry.label)
                                        .put("sortOrder", entry.sortOrder)
                                        .put("hidden", entry.hidden),
                                )
                            }
                        },
                    )
                    put("type", "codeList")
                },
            )
            if (sourceType != "INLINE") {
                set(
                    "source",
                    mapper.createObjectNode()
                        .put("type", sourceType)
                        .put("url", sourceUrl)
                        .put("auth", authType)
                        .put("credential", credentialId),
                )
            }
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
