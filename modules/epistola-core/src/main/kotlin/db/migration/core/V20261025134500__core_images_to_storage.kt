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
import java.sql.Connection

/**
 * Every draft's images move to JSON storage (ADR 0033, step 6): each `assets` row becomes its
 * content in `resource_contents`, `{ "resource": <its wire form> }`, a draft row in
 * `release_resources` naming it, and a `resource_binaries` link from the content to its bytes in
 * `asset_content`, which do not move. The next migration drops the table.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. What it knows is written into it and never
 * changes: how the asset columns of this time map to the wire form of this time (`ImageResource` of
 * contract 2.0: key, name, mediaType, width, height, contentHash; type), and the content hash
 * (SHA-256 of the JSON with every object's keys sorted). Never refused: an image whose bytes 1.x
 * had already lost keeps its row with no hash, logged. A font file whose bytes 1.x had lost (no
 * `image/` media type, no hash; the fonts step took every other one) is not an image: it is logged and
 * left behind, unlinked. `ImageStorageConversion` proves the suite
 * stores every bundled and demo image exactly as this converts it.
 */
@Suppress("ClassName")
class V20261025134500__core_images_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        var converted = 0
        var withoutBytes = 0
        var leftBehind = 0
        connection.prepareStatement(
            """
            SELECT tenant_key, catalog_id::text, version_id::text, resource_id::text, id, name, media_type, width, height, content_hash,
                   created_at, created_by::text
            FROM assets
            ORDER BY tenant_key, version_id, id
            """,
        ).use { select ->
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val tenant = rows.getString("tenant_key")
                    val binary = rows.getString("content_hash")
                    if (binary == null && !rows.getString("media_type").startsWith("image/")) {
                        leftBehind++
                        log.warn("File {} of tenant {} ({}) is no image and its bytes were lost before the upgrade; it is left behind", rows.getString("id"), tenant, rows.getString("media_type"))
                        continue
                    }
                    if (binary == null) {
                        withoutBytes++
                        log.warn("Image {} of tenant {} has no bytes; it is kept without them", rows.getString("id"), tenant)
                    }
                    val payload = payload(
                        key = rows.getString("id"),
                        name = rows.getString("name"),
                        mediaType = rows.getString("media_type"),
                        width = rows.getObject("width") as Int?,
                        height = rows.getObject("height") as Int?,
                        contentHash = binary,
                    )
                    val canonical = canonical(payload)
                    val hash = contentHash(canonical)
                    storeContent(connection, tenant, hash, canonical)
                    if (binary != null) linkBinary(connection, tenant, hash, binary)
                    connection.prepareStatement(
                        """
                        INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id,
                            content_hash, name, description, revision, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?::uuid, ?::uuid, 'image', ?, ?::uuid, ?, ?, NULL, 1, ?, ?, ?::uuid, ?::uuid)
                        """,
                    ).use { insert ->
                        insert.setString(1, tenant)
                        insert.setString(2, rows.getString("catalog_id"))
                        insert.setString(3, rows.getString("version_id"))
                        insert.setString(4, rows.getString("id"))
                        insert.setString(5, rows.getString("resource_id"))
                        insert.setString(6, hash)
                        insert.setString(7, rows.getString("name"))
                        insert.setTimestamp(8, rows.getTimestamp("created_at"))
                        insert.setTimestamp(9, rows.getTimestamp("created_at"))
                        insert.setString(10, rows.getString("created_by"))
                        insert.setString(11, rows.getString("created_by"))
                        insert.executeUpdate()
                    }
                    converted++
                }
            }
        }
        log.info("Moved {} images to JSON storage", converted)
        if (withoutBytes > 0) log.warn("{} image(s) had no bytes and are kept without them", withoutBytes)
        if (leftBehind > 0) log.warn("{} file(s) that are no image and had no bytes were left behind", leftBehind)
    }

    private fun storeContent(connection: Connection, tenant: String, hash: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, 'image', ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, canonical)
            it.executeUpdate()
        }
    }

    private fun linkBinary(connection: Connection, tenant: String, hash: String, binary: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_binaries (tenant_key, content_hash, binary_hash)
            SELECT ?, ?, content_hash FROM asset_content WHERE content_hash = ?
            ON CONFLICT DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, binary)
            it.executeUpdate()
        }
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 883419868

        private val mapper = JsonMapper.builder().build()

        /** The image's content from its columns: `{ "resource": <ImageResource> }`, width, height and hash null where the column is. */
        fun payload(key: String, name: String, mediaType: String, width: Int?, height: Int?, contentHash: String?): ObjectNode = mapper.createObjectNode().set(
            "resource",
            mapper.createObjectNode().apply {
                put("key", key)
                put("name", name)
                put("mediaType", mediaType)
                put("width", width)
                put("height", height)
                put("contentHash", contentHash)
                put("type", "image")
            },
        )

        /** The JSON the content hash is taken over: every object's keys sorted, at any depth. */
        fun canonical(payload: JsonNode): String = mapper.writeValueAsString(sortKeys(payload))

        /** SHA-256 of [canonical], lowercase hex. */
        fun contentHash(canonical: String): String = java.security.MessageDigest.getInstance("SHA-256")
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
