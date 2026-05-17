package app.epistola.suite.fonts.model

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.mapper.reflect.ColumnName
import java.time.OffsetDateTime

/**
 * A font family: a thin, catalog-scoped grouping over up to four font-face
 * binaries (regular / bold / italic / bold-italic). Tenant + catalog scoped,
 * mirroring the code lists / assets it groups.
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
 * One of the up-to-four faces a font family can carry. Stored and read as the
 * lowercase wire form to match the `FONT_VARIANT` SQL domain.
 */
enum class FontVariant(val wire: String) {
    REGULAR("regular"),
    BOLD("bold"),
    ITALIC("italic"),
    BOLD_ITALIC("bold_italic"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        fun fromWire(wire: String): FontVariant = BY_WIRE[wire]
            ?: throw IllegalArgumentException("Unknown font variant: $wire")
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
 * A single font-face pointer. Exactly one of [assetKey] / [classpathLocation]
 * is non-null, matching the `chk_font_variant_source` SQL CHECK.
 */
data class FontVariantRow(
    val variant: FontVariant,
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
