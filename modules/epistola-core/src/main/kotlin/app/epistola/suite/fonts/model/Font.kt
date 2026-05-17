package app.epistola.suite.fonts.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.mapper.reflect.ColumnName
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Lowercase hex SHA-256 of [bytes]. The single digest used both to pin a
 * face's bytes in [content_hash][FontVariantRow] (at import) and to derive a
 * family fingerprint (at publish) — so a delete+re-upload under the same
 * `(slug, weight, italic)` with different bytes flips the fingerprint and a
 * published render fails loudly.
 */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

/**
 * A font family: a thin, catalog-scoped grouping over N font-face binaries,
 * each keyed by a CSS numeric weight (1–1000) + italic flag. Tenant + catalog
 * scoped, mirroring the code lists / assets it groups.
 *
 * Variants are loaded separately via `GetFontVariants` — the listing UI only
 * needs the family metadata.
 */
data class Font(
    val slug: FontKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val name: String,
    val kind: FontKind,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    /**
     * Type of the catalog the font lives in, populated when the row is read
     * via a JOIN to catalogs. Used by the UI to gate edit affordances. Null
     * when the row is read without the join. Explicit `@ColumnName` because
     * JDBI's Kotlin plugin doesn't always reach JOIN aliases via default
     * snake_case mapping when the field has a default value.
     */
    @get:ColumnName("catalog_type")
    val catalogType: CatalogType? = null,
)

/**
 * Coarse classification of a font family. Stored and read as the lowercase
 * wire form (`sans` / `serif` / `mono` / `condensed` / `display`) to match the
 * `chk_fonts_kind` SQL CHECK.
 */
enum class FontKind(val wire: String) {
    SANS("sans"),
    SERIF("serif"),
    MONO("mono"),
    CONDENSED("condensed"),
    DISPLAY("display"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        fun fromWire(wire: String): FontKind = BY_WIRE[wire]
            ?: throw IllegalArgumentException("Unknown font kind: $wire")
    }
}

/**
 * Where a variant's binary lives:
 *
 * - `ASSET`     — an ordinary `assets` row in the same catalog (uploaded
 *   font binary; reuses all asset machinery).
 * - `CLASSPATH` — a bundled system font shipped once in the JAR (no asset
 *   row, no per-tenant copy).
 */
enum class FontVariantSource {
    ASSET,
    CLASSPATH,
}

/**
 * A single font-face pointer, keyed by CSS numeric [weight] (1–1000;
 * 400 = regular, 700 = bold) + [italic]. Exactly one of [assetKey] /
 * [classpathLocation] is non-null, matching the `chk_font_variant_source`
 * SQL CHECK. Every face is a static binary — variable fonts are instanced
 * into static faces at upload, never represented as a face type here.
 */
data class FontVariantRow(
    val weight: Int,
    val italic: Boolean,
    val source: FontVariantSource,
    val assetKey: AssetKey? = null,
    val classpathLocation: String? = null,
)

/**
 * Describes a theme or template version that references a font family via a
 * structured `{ slug, catalogKey }` `fontFamily` style ref. Mirrors
 * `app.epistola.suite.assets.AssetUsage`.
 */
data class FontUsage(
    val kind: String,
    val name: String,
)

/**
 * Thrown when attempting to delete a font family that is still referenced by a
 * theme or template version. Mirrors `app.epistola.suite.assets.AssetInUseException`.
 */
class FontInUseException(
    val slug: FontKey,
    val usages: List<FontUsage>,
) : RuntimeException(
    "Cannot delete font '${slug.value}': it is used by ${usages.joinToString { "${it.kind} '${it.name}'" }}",
)

/**
 * Thrown when rendering a *published* template version whose pinned per-family
 * font fingerprint no longer matches the live font family — i.e. a face was
 * deleted, added, or re-uploaded with different bytes since publish. The
 * published version is deterministic-or-nothing: rather than silently
 * re-rendering with changed glyphs, the render fails loudly. Republishing the
 * template version re-pins the current bytes.
 */
class FontIntegrityException(message: String) : RuntimeException(message)
