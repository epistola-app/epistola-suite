// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.revisions.ResourceContentStore
import app.epistola.suite.catalog.revisions.ResourceMetadata
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.Font
import app.epistola.suite.fonts.model.FontFace
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.sha256Hex
import app.epistola.suite.storage.AssetContentStore
import com.github.benmanes.caffeine.cache.Caffeine
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The columns [Font] maps from, for a query over `current_fonts f`: the fonts of the version a
 * caller means (ADR 0033). The catalog's key and namespace are looked up by key, row by row, rather
 * than joined (ThemePlanStabilityTest).
 */
internal const val FONT_COLUMNS: String =
    "f.slug, f.tenant_key, " +
        "(SELECT c.catalog_key FROM catalogs c WHERE c.tenant_key = f.tenant_key AND c.catalog_id = f.catalog_id) AS catalog_key, " +
        "(SELECT c.namespace FROM catalogs c WHERE c.tenant_key = f.tenant_key AND c.catalog_id = f.catalog_id) AS namespace, " +
        "f.name, f.kind, f.created_at, f.updated_at"

/**
 * The fonts of a draft, stored as JSON (ADR 0033, step 5): `{ "resource": <FontResource> }` in
 * `resource_contents`, named by the draft's row ([DraftResources]). A face's file is a binary of the
 * font: its bytes are in `asset_content` under its hash, and the content links
 * to them (`resource_binaries`, written when the content is stored), so they are kept while any
 * content names them. No asset row stands for a face.
 */
@Component
class FontStore(
    private val objectMapper: ObjectMapper,
    private val contents: ResourceContentStore,
    private val binaries: AssetContentStore,
) {

    /** The content of font [key]: its faces by italic, then weight, as a release of it orders them. */
    fun payload(key: String, name: String, kind: String, faces: List<FontFace>): JsonNode = objectMapper.createObjectNode().set(
        "resource",
        objectMapper.valueToTree<JsonNode>(
            FontResource(
                key = key,
                name = name,
                kind = FontKind.fromWire(kind).wire,
                variants = faces.sortedWith(compareBy({ it.italic }, { it.weight }))
                    .map { FontVariantEntry(weight = it.weight, italic = it.italic, mediaType = it.mediaType, contentHash = it.contentHash) },
            ),
        ),
    )

    /** One font family to write: its slug, display name, kind and faces. */
    data class FontSpec(
        val slug: String,
        val name: String,
        val kind: String,
        val variants: List<ImportFontVariant>,
        /** Producer metadata to store (contract `CatalogResource.metadata`); null keeps what the draft holds, empty clears it. */
        val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
    )

    /** Creates or replaces font [slug] of the targeted draft of [catalog]: INSTALLED when it is new, else UPDATED. */
    fun put(handle: Handle, catalog: CatalogId, slug: String, name: String, kind: String, variants: List<ImportFontVariant>, actor: UUID?, metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null): InstallStatus = putAll(handle, catalog, listOf(FontSpec(slug, name, kind, variants, metadata)), actor).getValue(slug)

    /**
     * Writes several families of [catalog]'s targeted draft. A family whose content is the one its
     * row names already is left alone, so installing a catalog again writes nothing. Its faces are
     * still linked to their bytes, which a family stored before its files were (the 2.0 upgrade's
     * conversion of 1.x's bundled fonts) does not have yet.
     */
    fun putAll(handle: Handle, catalog: CatalogId, fonts: List<FontSpec>, actor: UUID?): Map<String, InstallStatus> {
        if (fonts.isEmpty()) return emptyMap()
        storeBytes(fonts.flatMap { it.variants })
        linkStoredFaces(handle, catalog.tenantKey, fonts.flatMap { it.variants }.map { it.contentHash })
        val current = currentHashes(handle, catalog)
        return fonts.associate { font ->
            val payload = ResourceMetadata.carried(handle, catalog, FONT, font.slug, payload(font.slug, font.name, font.kind, font.variants.map(ImportFontVariant::face)), font.metadata, objectMapper)
            val existing = current[font.slug]
            if (existing != null && existing == contents.hashOf(payload)) return@associate font.slug to InstallStatus.SKIPPED
            val digest = contents.store(handle, catalog.tenantKey, FONT, payload)
            if (existing != null && DraftResources.update(handle, catalog, FONT, font.slug, digest, font.name, null, actor) != null) {
                font.slug to InstallStatus.UPDATED
            } else {
                DraftResources.insert(handle, catalog, FONT, font.slug, digest, font.name, null, actor)
                font.slug to InstallStatus.INSTALLED
            }
        }
    }

    /** The faces' bytes, those not stored yet: the lookup sends no bytes. */
    private fun storeBytes(variants: List<ImportFontVariant>) {
        val byHash = variants.associateBy { it.contentHash }
        val missing = byHash.keys.filterNot { binaries.exists(it) }
        for (hash in missing) {
            val variant = byHash.getValue(hash)
            binaries.putIfAbsent(hash, variant.content, variant.mediaType, variant.content.size.toLong())
        }
    }

    private fun currentHashes(handle: Handle, catalog: CatalogId): Map<String, String> = handle.createQuery(
        """
        SELECT r.resource_key, r.content_hash FROM release_wire_resources r
        WHERE r.tenant_key = :t AND r.resource_type = 'font'
          AND r.version_id = (SELECT v.version_id FROM catalog_current_versions v WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")})
        """,
    ).bind("t", catalog.tenantKey).bindCatalog("c", catalog)
        .map { rs, _ -> rs.getString("resource_key") to rs.getString("content_hash").trim() }
        .list().toMap()

    /**
     * Links every font content of [tenant], a draft's or a release's, that names a face among
     * [hashes] to its bytes, where it has no link yet: content stored before the bytes were.
     */
    fun linkStoredFaces(handle: Handle, tenant: app.epistola.suite.common.ids.TenantKey, hashes: List<String>) {
        if (hashes.isEmpty()) return
        handle.createUpdate(
            """
            INSERT INTO resource_binaries (tenant_key, content_hash, binary_hash)
            SELECT rc.tenant_key, rc.content_hash, face ->> 'contentHash'
            FROM resource_contents rc
            CROSS JOIN LATERAL jsonb_array_elements(COALESCE(rc.payload -> 'resource' -> 'variants', rc.payload -> 'variants')) face
            WHERE rc.tenant_key = :t AND rc.kind = 'font' AND face ->> 'contentHash' IN (<hashes>)
              AND EXISTS (SELECT 1 FROM asset_content ac WHERE ac.content_hash = CAST(face ->> 'contentHash' AS CHAR(64)))
            ON CONFLICT DO NOTHING
            """,
        ).bind("t", tenant).bindList("hashes", hashes.distinct()).execute()
    }

    fun delete(handle: Handle, id: FontId): Boolean = DraftResources.delete(handle, id.catalogId, FONT, id.key.value)

    /** Moves font [key] of [source] to [targetKey] of [target], its content renamed; false when there is none. */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean {
        val font = get(handle, FontId(FontKey.of(key), source)) ?: return false
        val payload = ResourceMetadata.with(payload(targetKey, font.name, font.kind.wire, faces(handle, FontId(FontKey.of(key), source))), ResourceMetadata.held(handle, source, FONT, key))
        return DraftResources.move(handle, source, FONT, key, target, targetKey, contents.store(handle, source.tenantKey, FONT, payload))
    }

    /** Font [id] as the version the handle means holds it, or null. */
    fun get(handle: Handle, id: FontId): Font? = handle
        .createQuery("SELECT $FONT_COLUMNS FROM current_fonts f WHERE f.tenant_key = :t AND f.catalog_id = ${catalogAt("t", "c")} AND f.slug = :k")
        .bind("t", id.tenantKey).bindCatalog("c", id.catalogId).bind("k", id.key.value)
        .mapTo(Font::class.java).findOne().orElse(null)

    /** The faces of font [id], by italic, then weight. */
    fun faces(handle: Handle, id: FontId): List<FontFace> = handle
        .createQuery("SELECT f.variants::text FROM current_fonts f WHERE f.tenant_key = :t AND f.catalog_id = ${catalogAt("t", "c")} AND f.slug = :k")
        .bind("t", id.tenantKey).bindCatalog("c", id.catalogId).bind("k", id.key.value)
        .mapTo(String::class.java).findOne().orElse(null)
        ?.let(::facesOf).orEmpty()

    /** The faces a stored `variants` array names. */
    fun facesOf(variants: String): List<FontFace> = objectMapper.readTree(variants).toList().map { face ->
        FontFace(face.path("weight").asInt(), face.path("italic").asBoolean(), face.path("mediaType").asString(), face.path("contentHash").asString())
    }

    /** The bytes of the face stored under [contentHash] that a content of the tenant links to, or null. */
    fun bytes(handle: Handle, tenant: app.epistola.suite.common.ids.TenantKey, contentHash: String): ByteArray? = handle.createQuery(
        """
        SELECT c.content FROM resource_binaries b
        JOIN asset_content c ON c.content_hash = b.binary_hash
        WHERE b.tenant_key = :t AND b.binary_hash = :hash
        LIMIT 1
        """,
    ).bind("t", tenant).bind("hash", contentHash).mapTo(ByteArray::class.java).findOne().orElse(null)

    companion object {
        /** The resource type, and content kind, of a font. */
        const val FONT = "font"

        /**
         * The hash a face's bytes are stored under, remembered per array: weakly, by identity, and
         * bounded. A bundled catalog's binaries are one array each per JVM ([app.epistola.suite.catalog.ClasspathCatalogCache]),
         * installed for every tenant, and hashing the system fonts again for each one was a sixth of
         * creating a tenant. An array is never written once it is read, so its hash cannot change.
         */
        fun hashOf(bytes: ByteArray): String = HASHES.get(bytes, ::sha256Hex)

        private val HASHES = Caffeine.newBuilder().weakKeys().maximumSize(4096).build<ByteArray, String>()
    }
}
