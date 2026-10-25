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
 * Every draft's fonts move to JSON storage (ADR 0033, step 5): each `fonts` row and its
 * `font_variants` become its content in `resource_contents`, `{ "resource": <its wire form> }`,
 * and a draft row in `release_resources` naming it. A face's file is the font's own binary: a
 * `resource_binaries` link from the content to the bytes `asset_content` already holds under that
 * hash. The bytes do not move. A bundled face's bytes are not in the database yet; its link is
 * written when the system fonts are next seeded, which writes them. The next migration drops the
 * tables and the asset rows that only held a face's file.
 *
 * Self-contained (ADR 0035): JDBC and a JSON tree only. What it knows is written into it and never
 * changes: how the font columns of this time map to the wire form of this time (`FontResource` of
 * contract 2.0: key, name, kind, variants of weight, italic, mediaType and contentHash, ordered by
 * italic, then weight; type), and the content hash (SHA-256 of the JSON with every object's keys
 * sorted). A 1.x face whose row and asset name no hash, because its bytes are still in the 1.x
 * `content_store`, has those bytes stored in `asset_content` and its asset stamped first (SHA-256 of
 * the bytes, as the suite stores assets), so it joins its font. A face whose hash is unknown and
 * whose bytes are nowhere is left out, logged. `FontStorageConversion` proves the suite stores every
 * system font exactly as this converts it.
 */
@Suppress("ClassName")
class V20261025105724__core_fonts_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Pinned to this source (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = CHECKSUM

    override fun migrate(context: Context) {
        val connection = context.connection
        stampLegacyFaceAssets(connection)
        var converted = 0
        connection.prepareStatement(
            """
            SELECT tenant_key, catalog_id::text, version_id::text, resource_id::text, slug, name, kind, created_at, updated_at
            FROM fonts
            ORDER BY tenant_key, version_id, slug
            """,
        ).use { select ->
            select.executeQuery().use { rows ->
                while (rows.next()) {
                    val tenant = rows.getString("tenant_key")
                    val versionId = rows.getString("version_id")
                    val faces = faces(connection, tenant, versionId, rows.getString("resource_id"), rows.getString("slug"))
                    val payload = payload(rows.getString("slug"), rows.getString("name"), rows.getString("kind"), faces)
                    val canonical = canonical(payload)
                    val hash = contentHash(canonical)
                    storeContent(connection, tenant, hash, canonical)
                    faces.forEach { linkBinary(connection, tenant, hash, it.contentHash) }
                    connection.prepareStatement(
                        """
                        INSERT INTO release_resources (tenant_key, catalog_id, version_id, resource_type, resource_key, resource_id,
                            content_hash, name, description, revision, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?::uuid, ?::uuid, 'font', ?, ?::uuid, ?, ?, NULL, 1, ?, ?, NULL, NULL)
                        """,
                    ).use { insert ->
                        insert.setString(1, tenant)
                        insert.setString(2, rows.getString("catalog_id"))
                        insert.setString(3, versionId)
                        insert.setString(4, rows.getString("slug"))
                        insert.setString(5, rows.getString("resource_id"))
                        insert.setString(6, hash)
                        insert.setString(7, rows.getString("name"))
                        insert.setTimestamp(8, rows.getTimestamp("created_at"))
                        insert.setTimestamp(9, rows.getTimestamp("updated_at"))
                        insert.executeUpdate()
                    }
                    converted++
                }
            }
        }
        log.info("Moved {} font families to JSON storage", converted)
    }

    /**
     * Stores the bytes of every asset a face names that has no hash yet, because 1.x left them in
     * `content_store`, and stamps the asset: what `V20261025110000` does for every other asset, done
     * here for faces so they join their fonts. An asset whose legacy bytes are missing stays as it is.
     */
    private fun stampLegacyFaceAssets(connection: Connection) {
        val pending = connection.prepareStatement(
            """
            SELECT DISTINCT a.tenant_key, a.id, a.media_type, a.sensitive
            FROM font_variants fv
            JOIN assets a ON a.tenant_key = fv.tenant_key AND a.resource_id = fv.asset_resource_id
            WHERE fv.content_hash IS NULL AND a.content_hash IS NULL
            """,
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4).toString())) } } }
        for ((tenant, id, mediaType, sensitive) in pending) {
            val bytes = connection.prepareStatement("SELECT content FROM content_store WHERE key = ?").use { st ->
                st.setString(1, "assets/$tenant/$id")
                st.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
            } ?: continue
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            connection.prepareStatement(
                "INSERT INTO asset_content (scope, content_hash, content, content_type, size_bytes) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            ).use {
                it.setString(1, if (sensitive.toBoolean()) tenant else "global")
                it.setString(2, hash)
                it.setBytes(3, bytes)
                it.setString(4, mediaType)
                it.setLong(5, bytes.size.toLong())
                it.executeUpdate()
            }
            connection.prepareStatement("UPDATE assets SET content_hash = ? WHERE tenant_key = ? AND id = ? AND content_hash IS NULL").use {
                it.setString(1, hash)
                it.setString(2, tenant)
                it.setString(3, id)
                it.executeUpdate()
            }
        }
    }

    /** One face of a family as the wire form says it. */
    data class Face(val weight: Int, val italic: Boolean, val mediaType: String, val contentHash: String)

    private fun faces(connection: Connection, tenant: String, versionId: String, fontResourceId: String, slug: String): List<Face> = connection.prepareStatement(
        """
        SELECT fv.weight, fv.italic, fv.classpath_location, fv.content_hash,
               (SELECT a.content_hash FROM assets a WHERE a.tenant_key = fv.tenant_key AND a.resource_id = fv.asset_resource_id
                ORDER BY a.content_hash IS NULL LIMIT 1) AS asset_hash,
               (SELECT a.media_type FROM assets a WHERE a.tenant_key = fv.tenant_key AND a.resource_id = fv.asset_resource_id
                LIMIT 1) AS asset_media_type
        FROM font_variants fv
        WHERE fv.tenant_key = ? AND fv.version_id = ?::uuid AND fv.font_resource_id = ?::uuid
        ORDER BY fv.italic, fv.weight
        """,
    ).use { select ->
        select.setString(1, tenant)
        select.setString(2, versionId)
        select.setString(3, fontResourceId)
        select.executeQuery().use { rows ->
            buildList {
                while (rows.next()) {
                    val weight = rows.getInt("weight")
                    val italic = rows.getBoolean("italic")
                    val hash = rows.getString("content_hash") ?: rows.getString("asset_hash")
                    if (hash == null) {
                        log.warn("Font {} of tenant {}: face {}{} has no known content and is left out", slug, tenant, weight, if (italic) " italic" else "")
                        continue
                    }
                    val location = rows.getString("classpath_location")
                    val mediaType = rows.getString("asset_media_type")
                        ?: if (location?.endsWith(".otf", ignoreCase = true) == true) "font/otf" else "font/ttf"
                    add(Face(weight, italic, mediaType, hash))
                }
            }
        }
    }

    private fun storeContent(connection: Connection, tenant: String, hash: String, canonical: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_contents (tenant_key, content_hash, kind, payload) VALUES (?, ?, 'font', ?::jsonb)
            ON CONFLICT (tenant_key, content_hash) DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, canonical)
            it.executeUpdate()
        }
    }

    /** Links [hash]'s content to a face's bytes, in the tenant's own scope if it has them there, else the shared one. */
    private fun linkBinary(connection: Connection, tenant: String, hash: String, binaryHash: String) {
        connection.prepareStatement(
            """
            INSERT INTO resource_binaries (tenant_key, content_hash, scope, binary_hash)
            SELECT ?, ?, ac.scope, ac.content_hash FROM asset_content ac
            WHERE ac.content_hash = ? AND ac.scope IN (?, 'global')
            ORDER BY ac.scope = 'global'
            LIMIT 1
            ON CONFLICT DO NOTHING
            """,
        ).use {
            it.setString(1, tenant)
            it.setString(2, hash)
            it.setString(3, binaryHash)
            it.setString(4, tenant)
            it.executeUpdate()
        }
    }

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 381601423

        private val mapper = JsonMapper.builder().build()

        /** The font's content from its columns: `{ "resource": <FontResource> }`, its faces by italic, then weight. */
        fun payload(key: String, name: String, kind: String, faces: List<Face>): ObjectNode = mapper.createObjectNode().set(
            "resource",
            mapper.createObjectNode().apply {
                put("key", key)
                put("name", name)
                put("kind", kind)
                set(
                    "variants",
                    mapper.createArrayNode().also { variants ->
                        faces.sortedWith(compareBy({ it.italic }, { it.weight })).forEach { face ->
                            variants.add(
                                mapper.createObjectNode()
                                    .put("weight", face.weight)
                                    .put("italic", face.italic)
                                    .put("mediaType", face.mediaType)
                                    .put("contentHash", face.contentHash),
                            )
                        }
                    },
                )
                put("type", "font")
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
