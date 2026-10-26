// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.ResourceMetadata
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.storage.AssetContentStore
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The columns [IMAGE_ROWS] maps, for a query over `current_images a`: the images of the version a
 * caller means (ADR 0033). The catalog's key and namespace are looked up by key, row by row, rather
 * than joined (ThemePlanStabilityTest).
 */
internal const val IMAGE_COLUMNS: String =
    "a.id, a.tenant_key, " +
        "(SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id) AS catalog_key, " +
        "(SELECT c.namespace FROM catalogs c WHERE c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id) AS namespace, " +
        "a.name, a.media_type, a.size_bytes, a.width, a.height, a.content_hash, a.created_at, a.created_by"

/** An [Asset] from [IMAGE_COLUMNS]. */
internal val IMAGE_ROWS: RowMapper<Asset> = RowMapper { rs, _ ->
    Asset(
        id = AssetKey(rs.getString("id")),
        tenantKey = TenantKey(rs.getString("tenant_key")),
        catalogKey = CatalogKey.of(rs.getString("catalog_key")),
        namespace = rs.getString("namespace"),
        name = rs.getString("name"),
        mediaType = AssetMediaType.fromMimeType(rs.getString("media_type")),
        sizeBytes = rs.getLong("size_bytes"),
        width = rs.getObject("width", Int::class.javaObjectType),
        height = rs.getObject("height", Int::class.javaObjectType),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        createdBy = rs.getObject("created_by", UUID::class.java)?.let { UserKey(it) },
    )
}

/** What an image says: everything its content holds but its key. */
data class ImageFields(
    val name: String,
    val mediaType: AssetMediaType,
    val width: Int?,
    val height: Int?,
    /** The SHA-256 of its bytes, which `asset_content` holds under it. */
    val contentHash: String,
    /** Producer metadata to store (contract `CatalogResource.metadata`); null keeps what the draft holds, empty clears it. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
)

/**
 * The images of a draft, stored as JSON (ADR 0033, step 6): `{ "resource": <ImageResource> }` in
 * `resource_contents`, named by the draft's row ([DraftResources]), its bytes in `asset_content`
 * under their hash, once per installation, and linked from the content (`resource_binaries`, written
 * when it is stored), so they are kept while any content names them.
 */
@Component
class ImageStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
    private val binaries: AssetContentStore,
    private val assetTypes: AssetTypeCatalog,
) {

    /** The content of image [key]. */
    fun payload(key: String, fields: ImageFields): JsonNode = objectMapper.createObjectNode().set(
        "resource",
        objectMapper.valueToTree<JsonNode>(
            ImageResource(key = key, name = fields.name, mediaType = fields.mediaType.mimeType, width = fields.width, height = fields.height, contentHash = fields.contentHash),
        ),
    )

    /**
     * Stores [bytes] once, under their hash, and returns it; an unsupported media type is refused
     * ([UnsupportedAssetTypeException]). Bytes stored already are not sent again.
     * Before the content that names them, so a content never links to bytes that are not there.
     */
    fun storeBytes(bytes: ByteArray, mediaType: AssetMediaType): String {
        // A media type the installation does not accept (`asset_types`) is refused, as the asset
        // table's foreign key refused it before images were stored as JSON.
        assetTypes.require(mediaType.mimeType)
        val hash = sha256Hex(bytes)
        if (!binaries.exists(hash)) binaries.putIfAbsent(hash, bytes, mediaType.mimeType, bytes.size.toLong())
        return hash
    }

    private fun store(handle: Handle, catalog: CatalogId, key: String, fields: ImageFields): String = contents.store(handle, catalog.tenantKey, IMAGE, ResourceMetadata.carried(handle, catalog, IMAGE, key, payload(key, fields), fields.metadata, objectMapper))

    /** Adds image [key] to the targeted draft of [catalog]; one already there is a unique violation. */
    fun create(handle: Handle, catalog: CatalogId, key: String, fields: ImageFields, actor: UUID?): Asset {
        DraftResources.insert(handle, catalog, IMAGE, key, store(handle, catalog, key, fields), fields.name, null, actor)
        return checkNotNull(get(handle, catalog, key)) { "Image $key was just written" }
    }

    /** Creates or replaces image [key] of the targeted draft; true when it was there already. */
    fun put(handle: Handle, catalog: CatalogId, key: String, fields: ImageFields, actor: UUID?): Boolean {
        val digest = store(handle, catalog, key, fields)
        if (DraftResources.update(handle, catalog, IMAGE, key, digest, fields.name, null, actor) != null) return true
        DraftResources.insert(handle, catalog, IMAGE, key, digest, fields.name, null, actor)
        return false
    }

    fun delete(handle: Handle, catalog: CatalogId, key: String): Boolean = DraftResources.delete(handle, catalog, IMAGE, key)

    /** Moves image [key] of [source] to [targetKey] of [target], its content renamed; false when there is none. */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean {
        val image = get(handle, source, key) ?: return false
        val hash = handle.createQuery("SELECT content_hash FROM current_images WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k")
            .bind("t", source.tenantKey).bindCatalog("c", source).bind("k", key).mapTo(String::class.java).one()
        val fields = ImageFields(image.name, image.mediaType, image.width, image.height, hash, ResourceMetadata.moving(handle, source, IMAGE, key))
        return DraftResources.move(handle, source, IMAGE, key, target, targetKey, store(handle, source, targetKey, fields))
    }

    /** Image [key] of [catalog] as the version the handle means holds it, or null. */
    fun get(handle: Handle, catalog: CatalogId, key: String): Asset? = handle
        .createQuery("SELECT $IMAGE_COLUMNS FROM current_images a WHERE a.tenant_key = :t AND a.catalog_id = ${catalogAt("t", "c")} AND a.id = :k")
        .bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("k", key)
        .map(IMAGE_ROWS).findOne().orElse(null)

    companion object {
        /** The resource type, and content kind, of an image. */
        const val IMAGE = "image"
    }
}
