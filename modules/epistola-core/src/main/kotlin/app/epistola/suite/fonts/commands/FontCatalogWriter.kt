// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts.commands

import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.model.sha256Hex
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared writer for a font family + its variants on a caller-owned JDBI [Handle].
 *
 * Used by [ImportFontHandler] (one family per transaction) and the system-font
 * seeder (all eight bundled families in a *single* transaction — see
 * `EnsureSystemFontsHandler`). Centralising the SQL here means the seeder can
 * batch without duplicating logic, and both paths share:
 * - the upsert that reports INSTALLED vs UPDATED via `xmax = 0` (no separate
 *   existence SELECT), and
 * - the per-JVM classpath content-hash cache (bundled face bytes are immutable,
 *   so the same TTF is read+hashed once, not once per tenant).
 */
@Component
class FontCatalogWriter {
    /**
     * UPSERT [slug]'s family row and atomically replace its variants. The caller
     * owns the transaction, so several families can share one. Returns INSTALLED
     * for a newly inserted family, UPDATED otherwise.
     *
     * [assetBytes] supplies the bytes of an ASSET-backed face so its content hash
     * can be computed; classpath faces are hashed from cached classpath bytes.
     */
    /** One font family to write: its slug, display name, kind and faces. */
    data class FontSpec(
        val slug: String,
        val name: String,
        val kind: String,
        val variants: List<ImportFontVariant>,
    )

    fun writeFont(
        handle: Handle,
        tenantId: TenantId,
        catalogKey: CatalogKey,
        slug: String,
        name: String,
        kind: String,
        variants: List<ImportFontVariant>,
        assetBytes: (variant: ImportFontVariant) -> ByteArray? = { null },
    ): InstallStatus = writeFonts(handle, tenantId, catalogKey, listOf(FontSpec(slug, name, kind, variants)), assetBytes).getValue(slug)

    /**
     * Writes several families in three statements — one multi-row upsert of the families, one
     * delete of their previous faces, one batch insert of the new faces — instead of three per
     * family. The eight bundled families are seeded into every new tenant, so this is what turns
     * twenty-four round trips per tenant into three. Returns the install status per slug.
     */
    fun writeFonts(
        handle: Handle,
        tenantId: TenantId,
        catalogKey: CatalogKey,
        fonts: List<FontSpec>,
        assetBytes: (variant: ImportFontVariant) -> ByteArray? = { null },
    ): Map<String, InstallStatus> {
        if (fonts.isEmpty()) return emptyMap()
        val tenantKey = tenantId.key
        val keyed = fonts.map { it to FontKey.of(it.slug) }
        val tuples = keyed.indices.joinToString(",\n") { i ->
            "(:slug$i, :tenantKey, :catalogKey, :name$i, :kind$i, NOW(), NOW())"
        }
        val upsert = handle.createQuery(
            """
            INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind, created_at, updated_at)
            VALUES
            $tuples
            ON CONFLICT (tenant_key, catalog_key, slug) DO UPDATE
            SET name       = EXCLUDED.name,
                kind       = EXCLUDED.kind,
                updated_at = NOW()
            RETURNING slug, (xmax = 0) AS inserted
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
        keyed.forEachIndexed { i, (font, fontSlug) ->
            upsert.bind("slug$i", fontSlug)
                .bind("name$i", font.name)
                .bind("kind$i", FontKind.fromWire(font.kind).wire)
        }
        val inserted = upsert.map { rs, _ -> rs.getString("slug") to rs.getBoolean("inserted") }.toMap()
        handle.createUpdate(
            """
            DELETE FROM font_variants
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND font_slug IN (<slugs>)
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .bindList("slugs", keyed.map { it.second })
            .execute()
        val batch = handle.prepareBatch(
            """
            INSERT INTO font_variants
                (tenant_key, catalog_key, font_slug, weight, italic, source, asset_key, asset_catalog_key, classpath_location, content_hash)
            VALUES (:tenantKey, :catalogKey, :slug, :weight, :italic, :source, :assetKey, :assetCatalogKey, :classpathLocation, :contentHash)
            """,
        )
        var faces = 0
        for ((font, fontSlug) in keyed) {
            for (variant in font.variants) {
                batch.bind("tenantKey", tenantKey)
                    .bind("catalogKey", catalogKey)
                    .bind("slug", fontSlug)
                    .bind("weight", variant.weight)
                    .bind("italic", variant.italic)
                    .bind("source", variant.source.name)
                    .bind("assetKey", variant.assetKey?.value)
                    // A face's asset is written into the same catalog as the family; the column is
                    // separate so a later relocation of either can move without the other.
                    .bind("assetCatalogKey", variant.assetKey?.let { catalogKey })
                    .bind("classpathLocation", variant.classpathLocation)
                    .bind("contentHash", contentHash(variant, assetBytes))
                    .add()
                faces++
            }
        }
        if (faces > 0) batch.execute()
        return keyed.associate { (font, fontSlug) ->
            font.slug to if (inserted[fontSlug.value] == true) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }
    }

    private fun contentHash(variant: ImportFontVariant, assetBytes: (ImportFontVariant) -> ByteArray?): String? = when (variant.source) {
        FontVariantSource.CLASSPATH -> variant.classpathLocation?.let(::classpathContentHash)
        FontVariantSource.ASSET -> runCatching { assetBytes(variant) }.getOrNull()?.let(::sha256Hex)
    }

    private fun classpathContentHash(location: String): String? = CLASSPATH_HASH_CACHE.computeIfAbsent(location) { loc ->
        Optional.ofNullable(
            runCatching { javaClass.classLoader.getResourceAsStream(loc)?.readBytes() }
                .getOrNull()
                ?.let(::sha256Hex),
        )
    }.orElse(null)

    private companion object {
        /** location -> SHA-256 hex (absent value = unreadable), computed once per JVM. */
        private val CLASSPATH_HASH_CACHE = ConcurrentHashMap<String, Optional<String>>()
    }
}
