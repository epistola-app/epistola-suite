// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core

import db.migration.core.frozen.Envelopes
import db.migration.core.frozen.FrozenToolkit
import db.migration.core.frozen.OneXContent
import db.migration.core.frozen.ReferenceWalker
import db.migration.core.frozen.StorageWrites
import db.migration.core.frozen.TemplateContentV8
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * The 2.0 upgrade, step 6 of 16 (docs/migrations.md, "The 2.0 chain"): a 1.x catalog's themes, code
 * lists, attributes, fonts and images become its next draft's content in JSON storage (ADR 0033).
 * None of them was versioned in 1.x: what a catalog held is what it published, so each is marked ready.
 *
 * - A **theme** gets the wire-v8 font renames in its styles ([TemplateContentV8.styles]).
 * - A **code list** holds its entries by sort order and code, and beside the released form the source
 *   it is refreshed from; a credential moves, still encrypted, to `resource_credentials`, which the
 *   source names by id, and the last refresh to `code_list_refreshes`.
 * - An **attribute** bound to a code list names it by the alias of its catalog: its key, as this
 *   installation declares every dependency (step 10), or none for a list of its own catalog.
 * - A **font** holds its faces by italic, then weight, each by the hash of its file, linked as the
 *   content's binary. A face without a known file is left out. A file that only held a face (not an
 *   image, named by no template or stencil as an image) is no image: it goes with its asset row.
 * - An **image** keeps its file by hash; one whose bytes were lost before the upgrade is kept without
 *   them, but a file that is no image and lost its bytes is left behind.
 *
 * Every row keeps its identity, revision, and who created and last changed it, and when, where 1.x
 * kept them. Self-contained (ADR 0035): JDBC and the frozen toolkit.
 */
@Suppress("ClassName")
class V20261101000600__core_2_0_settings_to_storage : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    /** Pinned to this source and the toolkit (ADR 0035): Flyway records it, so an edit after it ran is refused. */
    override fun getChecksum(): Int = FrozenToolkit.pinned(CHECKSUM)

    override fun migrate(context: Context) {
        val store = StorageWrites(context.connection)
        val themes = themes(store)
        val codeLists = codeLists(store)
        val attributes = attributes(store)
        val fonts = fonts(store)
        val images = images(store, OneXContent.load(context.connection))
        log.info("2.0 upgrade: {} theme(s), {} code list(s), {} attribute(s), {} font(s), {} image(s) moved to JSON storage", themes, codeLists, attributes, fonts, images)
    }

    /** The columns every 1.x resource row shares, with its catalog's identity and next draft. */
    private class Owner(val tenant: String, val catalogId: String, val catalogKey: String, val versionId: String, val resourceId: String)

    private fun owner(rs: ResultSet) = Owner(rs.getString("tenant_key"), rs.getString("catalog_id"), rs.getString("catalog_key"), rs.getString("version_id"), rs.getString("resource_id"))

    private val ownerColumns = "x.tenant_key, c.catalog_id::text AS catalog_id, c.catalog_key::text AS catalog_key, c.next_version_id::text AS version_id, x.resource_id::text AS resource_id"
    private val ownerJoin = "JOIN catalogs c ON c.tenant_key = x.tenant_key AND c.catalog_key = x.catalog_key"

    private fun themes(store: StorageWrites): Int {
        var count = 0
        store.query(
            """
            SELECT $ownerColumns, x.id, x.name, x.description, x.document_styles::text, x.page_settings::text, x.block_style_presets::text,
                   x.spacing_unit, x.created_at, x.updated_at, x.created_by::text, x.updated_by::text
            FROM themes x $ownerJoin
            ORDER BY x.tenant_key, x.catalog_key, x.id
            """,
        ) { rs ->
            val owner = owner(rs)
            val payload = Envelopes.theme(
                key = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                documentStyles = TemplateContentV8.styles(mapper.readTree(rs.getString("document_styles") ?: "{}")),
                pageSettings = rs.getString("page_settings")?.let(mapper::readTree),
                blockStylePresets = TemplateContentV8.presets(rs.getString("block_style_presets")?.let(mapper::readTree)),
                spacingUnit = rs.getBigDecimal("spacing_unit"),
            )
            val row = StorageWrites.Row(
                owner.tenant, owner.catalogId, owner.versionId, "theme", rs.getString("id"), owner.resourceId, "", rs.getString("name"), rs.getString("description"),
                createdAt = rs.getTimestamp("created_at"), updatedAt = rs.getTimestamp("updated_at"), createdBy = rs.getString("created_by"), updatedBy = rs.getString("updated_by"),
            )
            payload to row
        }.forEach { (payload, row) ->
            val hash = store.store(row.tenant, "theme", payload, ReferenceWalker.references(payload))
            store.row(row.copy(hash))
            store.ready(row.tenant, row.versionId, row.resourceId, "", hash)
            count++
        }
        return count
    }

    private class CodeList(
        val owner: Owner,
        val key: String,
        val name: String,
        val description: String?,
        val sourceType: String,
        val sourceUrl: String?,
        val authType: String,
        val credential: String?,
        val refreshedAt: Timestamp?,
        val refreshError: String?,
        val createdAt: Timestamp,
        val updatedAt: Timestamp,
    )

    private fun codeLists(store: StorageWrites): Int {
        val lists = store.query(
            """
            SELECT $ownerColumns, x.slug, x.display_name, x.description, x.source_type::text, x.source_url, x.auth_type::text, x.credential,
                   x.last_refreshed_at, x.last_refresh_error, x.created_at, x.updated_at
            FROM code_lists x $ownerJoin
            ORDER BY x.tenant_key, x.catalog_key, x.slug
            """,
        ) { rs ->
            CodeList(
                owner(rs), rs.getString("slug"), rs.getString("display_name"), rs.getString("description"), rs.getString("source_type"), rs.getString("source_url"),
                rs.getString("auth_type"), rs.getString("credential"), rs.getTimestamp("last_refreshed_at"), rs.getString("last_refresh_error"),
                rs.getTimestamp("created_at"), rs.getTimestamp("updated_at"),
            )
        }
        for (list in lists) {
            val owner = list.owner
            // The secret moves as it is (the cipher binds only its key id); the content names it by id.
            val credentialId = list.credential?.let { encrypted ->
                store.query(
                    "INSERT INTO resource_credentials (tenant_key, credential_id, credential) VALUES (?, epistola_uuidv7(), ?) RETURNING credential_id::text",
                    owner.tenant,
                    encrypted,
                ) { it.getString(1) }.single()
            }
            val entries = store.query(
                "SELECT code, label, sort_order, hidden FROM code_list_entries WHERE tenant_key = ? AND code_list_resource_id = ?::uuid ORDER BY sort_order, code",
                owner.tenant,
                owner.resourceId,
            ) { Envelopes.CodeListEntry(it.getString(1), it.getString(2), it.getInt(3), it.getBoolean(4)) }
            val payload = Envelopes.codeList(list.key, list.name, list.description, entries, list.sourceType, list.sourceUrl, list.authType, credentialId)
            val hash = store.store(owner.tenant, "codeList", payload)
            store.row(
                StorageWrites.Row(
                    owner.tenant, owner.catalogId, owner.versionId, "codeList", list.key, owner.resourceId, hash, list.name, list.description,
                    createdAt = list.createdAt, updatedAt = list.updatedAt,
                ),
            )
            if (list.refreshedAt != null || list.refreshError != null) {
                store.update(
                    "INSERT INTO code_list_refreshes (tenant_key, version_id, resource_id, last_refreshed_at, last_refresh_error) VALUES (?, ?::uuid, ?::uuid, ?, ?)",
                    owner.tenant,
                    owner.versionId,
                    owner.resourceId,
                    list.refreshedAt,
                    list.refreshError,
                )
            }
            store.ready(owner.tenant, owner.versionId, owner.resourceId, "", hash)
        }
        return lists.size
    }

    private fun attributes(store: StorageWrites): Int {
        var count = 0
        store.query(
            """
            SELECT $ownerColumns, x.id, x.display_name, x.allowed_values::text, x.created_at, x.updated_at,
                   bound.resource_key AS bound_key,
                   CASE WHEN bound.catalog_id = c.catalog_id THEN NULL ELSE bound_catalog.catalog_key::text END AS bound_alias
            FROM variant_attribute_definitions x $ownerJoin
            LEFT JOIN catalog_resources bound ON bound.tenant_key = x.tenant_key AND bound.resource_id = x.code_list_resource_id
            LEFT JOIN catalogs bound_catalog ON bound_catalog.tenant_key = bound.tenant_key AND bound_catalog.catalog_id = bound.catalog_id
            ORDER BY x.tenant_key, x.catalog_key, x.id
            """,
        ) { rs ->
            val owner = owner(rs)
            val boundKey = rs.getString("bound_key")
            val boundAlias = rs.getString("bound_alias")
            val payload = Envelopes.attribute(rs.getString("id"), rs.getString("display_name"), rs.getString("allowed_values")?.let(mapper::readTree), boundAlias, boundKey)
            val row = StorageWrites.Row(
                owner.tenant, owner.catalogId, owner.versionId, "attribute", rs.getString("id"), owner.resourceId, "", rs.getString("display_name"),
                createdAt = rs.getTimestamp("created_at"), updatedAt = rs.getTimestamp("updated_at"),
            )
            Triple(payload, row, boundKey?.let { ReferenceWalker.Reference(boundAlias, "codeList", it) })
        }.forEach { (payload, row, binding) ->
            val hash = store.store(row.tenant, "attribute", payload, setOfNotNull(binding))
            store.row(row.copy(hash))
            store.ready(row.tenant, row.versionId, row.resourceId, "", hash)
            count++
        }
        return count
    }

    private class Font(val owner: Owner, val key: String, val name: String, val kind: String, val createdAt: Timestamp, val updatedAt: Timestamp)

    private fun fonts(store: StorageWrites): Int {
        val fonts = store.query(
            """
            SELECT $ownerColumns, x.slug, x.name, x.kind, x.created_at, x.updated_at
            FROM fonts x $ownerJoin
            ORDER BY x.tenant_key, x.catalog_key, x.slug
            """,
        ) { rs -> Font(owner(rs), rs.getString("slug"), rs.getString("name"), rs.getString("kind"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")) }
        for (font in fonts) {
            val owner = font.owner
            val faces = store.query(
                """
                SELECT fv.weight, fv.italic, fv.classpath_location, fv.content_hash,
                       (SELECT a.content_hash FROM assets a WHERE a.tenant_key = fv.tenant_key AND a.resource_id = fv.asset_resource_id
                        ORDER BY a.content_hash IS NULL LIMIT 1) AS asset_hash,
                       (SELECT a.media_type FROM assets a WHERE a.tenant_key = fv.tenant_key AND a.resource_id = fv.asset_resource_id
                        LIMIT 1) AS asset_media_type
                FROM font_variants fv
                WHERE fv.tenant_key = ? AND fv.font_resource_id = ?::uuid
                ORDER BY fv.italic, fv.weight
                """,
                owner.tenant,
                owner.resourceId,
            ) { rs ->
                val weight = rs.getInt("weight")
                val italic = rs.getBoolean("italic")
                val hash = rs.getString("content_hash") ?: rs.getString("asset_hash")
                if (hash == null) {
                    log.warn("Font {} of tenant {}: face {}{} has no known content and is left out", font.key, owner.tenant, weight, if (italic) " italic" else "")
                    null
                } else {
                    val location = rs.getString("classpath_location")
                    val mediaType = rs.getString("asset_media_type") ?: if (location?.endsWith(".otf", ignoreCase = true) == true) "font/otf" else "font/ttf"
                    Envelopes.Face(weight, italic, mediaType, hash)
                }
            }.filterNotNull()
            val hash = store.store(owner.tenant, "font", Envelopes.font(font.key, font.name, font.kind, faces))
            faces.forEach { store.binary(owner.tenant, hash, it.contentHash) }
            store.row(
                StorageWrites.Row(
                    owner.tenant, owner.catalogId, owner.versionId, "font", font.key, owner.resourceId, hash, font.name,
                    createdAt = font.createdAt, updatedAt = font.updatedAt,
                ),
            )
            store.ready(owner.tenant, owner.versionId, owner.resourceId, "", hash)
        }
        return fonts.size
    }

    private fun images(store: StorageWrites, content: OneXContent.Content): Int {
        // The image keys each tenant's templates and stencils name, as their working copies hold them.
        val named = mutableSetOf<Pair<String, String>>()
        store.query("SELECT v.tenant_key, v.template_resource_id::text, v.id FROM template_variants v") { Triple(it.getString(1), it.getString(2), it.getString(3)) }
            .forEach { (tenant, template, variant) ->
                ReferenceWalker.references(content.workingModel(tenant, template, variant)).filter { it.type == "image" }.forEach { named += tenant to it.key }
            }
        store.query("SELECT tenant_key, resource_id::text FROM stencils") { it.getString(1) to it.getString(2) }.forEach { (tenant, stencil) ->
            ReferenceWalker.references(content.stencil(tenant, stencil).content).filter { it.type == "image" }.forEach { named += tenant to it.key }
        }

        val images = store.query(
            """
            SELECT $ownerColumns, x.id, x.name, x.media_type, x.width, x.height, x.content_hash, x.created_at, x.created_by::text,
                   EXISTS (SELECT 1 FROM font_variants fv WHERE fv.tenant_key = x.tenant_key AND fv.asset_resource_id = x.resource_id) AS face,
                   EXISTS (SELECT 1 FROM asset_content ac WHERE ac.content_hash = CAST(x.content_hash AS CHAR(64))) AS stored
            FROM assets x $ownerJoin
            ORDER BY x.tenant_key, x.catalog_key, x.id
            """,
        ) { rs ->
            Image(
                owner(rs), rs.getString("id"), rs.getString("name"), rs.getString("media_type"), rs.getObject("width") as Int?, rs.getObject("height") as Int?,
                rs.getString("content_hash"), rs.getTimestamp("created_at"), rs.getString("created_by"), rs.getBoolean("face"), rs.getBoolean("stored"),
            )
        }
        var count = 0
        var withoutBytes = 0
        var leftBehind = 0
        var faceFiles = 0
        for (image in images) {
            val owner = image.owner
            val isImage = image.mediaType.startsWith("image/")
            if (!isImage && image.face && image.stored && (owner.tenant to image.key) !in named) {
                // A file that only held a font face: its bytes are its font's binary now (step 6, fonts).
                store.update("DELETE FROM catalog_resources WHERE tenant_key = ? AND resource_id = ?::uuid", owner.tenant, owner.resourceId)
                faceFiles++
                continue
            }
            if (image.binary == null && !isImage) {
                log.warn("File {} of tenant {} ({}) is no image and its bytes were lost before the upgrade; it is left behind", image.key, owner.tenant, image.mediaType)
                store.update("DELETE FROM catalog_resources WHERE tenant_key = ? AND resource_id = ?::uuid", owner.tenant, owner.resourceId)
                leftBehind++
                continue
            }
            if (image.binary == null) {
                log.warn("Image {} of tenant {} has no bytes; it is kept without them", image.key, owner.tenant)
                withoutBytes++
            }
            val hash = store.store(owner.tenant, "image", Envelopes.image(image.key, image.name, image.mediaType, image.width, image.height, image.binary))
            image.binary?.let { store.binary(owner.tenant, hash, it) }
            store.row(
                StorageWrites.Row(
                    owner.tenant, owner.catalogId, owner.versionId, "image", image.key, owner.resourceId, hash, image.name,
                    createdAt = image.createdAt, updatedAt = image.createdAt, createdBy = image.createdBy, updatedBy = image.createdBy,
                ),
            )
            store.ready(owner.tenant, owner.versionId, owner.resourceId, "", hash)
            count++
        }
        if (withoutBytes > 0) log.warn("{} image(s) had no bytes and are kept without them", withoutBytes)
        if (leftBehind > 0) log.warn("{} file(s) that are no image and had no bytes were left behind", leftBehind)
        if (faceFiles > 0) log.info("{} file(s) that only held a font face are now their font's binary", faceFiles)
        return count
    }

    private class Image(
        val owner: Owner,
        val key: String,
        val name: String,
        val mediaType: String,
        val width: Int?,
        val height: Int?,
        val binary: String?,
        val createdAt: Timestamp,
        val createdBy: String?,
        val face: Boolean,
        val stored: Boolean,
    )

    private fun StorageWrites.Row.copy(contentHash: String) = StorageWrites.Row(
        tenant, catalogId, versionId, type, key, resourceId, contentHash, name, description, parentId, revision, createdAt, updatedAt, createdBy, updatedBy,
    )

    companion object {
        /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
        const val CHECKSUM: Int = 1153267541
    }
}
