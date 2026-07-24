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
    fun writeFont(
        handle: Handle,
        tenantId: TenantId,
        catalogKey: CatalogKey,
        slug: String,
        name: String,
        kind: String,
        variants: List<ImportFontVariant>,
        assetBytes: (variant: ImportFontVariant) -> ByteArray? = { null },
    ): InstallStatus {
        val fontSlug = FontKey.of(slug)
        // Normalise through the enum so an unknown kind fails loudly here rather
        // than as an opaque CHECK violation.
        val kindWire = FontKind.fromWire(kind).wire
        val tenantKey = tenantId.key

        val inserted = handle.createQuery(
            """
            INSERT INTO fonts (slug, tenant_key, catalog_key, name, kind, created_at, updated_at)
            VALUES (:slug, :tenantKey, :catalogKey, :name, :kind, NOW(), NOW())
            ON CONFLICT (tenant_key, catalog_key, slug) DO UPDATE
            SET name       = :name,
                kind       = :kind,
                updated_at = NOW()
            RETURNING (xmax = 0) AS inserted
            """,
        )
            .bind("slug", fontSlug)
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .bind("name", name)
            .bind("kind", kindWire)
            .mapTo(Boolean::class.java)
            .one()

        // Replace variants atomically — the importing tenant has no local copy to
        // preserve; the catalog file is the source of record.
        handle.createUpdate(
            """
            DELETE FROM font_variants
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND font_slug = :slug
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("catalogKey", catalogKey)
            .bind("slug", fontSlug)
            .execute()

        if (variants.isNotEmpty()) {
            val batch = handle.prepareBatch(
                """
                INSERT INTO font_variants
                    (tenant_key, catalog_key, font_slug, weight, italic, source, asset_key, classpath_location, content_hash)
                VALUES (:tenantKey, :catalogKey, :slug, :weight, :italic, :source, :assetKey, :classpathLocation, :contentHash)
                """,
            )
            for (variant in variants) {
                batch.bind("tenantKey", tenantKey)
                    .bind("catalogKey", catalogKey)
                    .bind("slug", fontSlug)
                    .bind("weight", variant.weight)
                    .bind("italic", variant.italic)
                    .bind("source", variant.source.name)
                    .bind("assetKey", variant.assetKey?.value)
                    .bind("classpathLocation", variant.classpathLocation)
                    .bind("contentHash", contentHash(variant, assetBytes))
                    .add()
            }
            batch.execute()
        }

        return if (inserted) InstallStatus.INSTALLED else InstallStatus.UPDATED
    }

    /**
     * SHA-256 hex of a face's bytes, or `null` if they can't be loaded (a
     * never-hashed face fails a published render loudly rather than rendering
     * wrong). Classpath faces are cached by location; asset faces are hashed live.
     */
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
