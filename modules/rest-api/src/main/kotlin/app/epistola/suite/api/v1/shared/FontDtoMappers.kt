package app.epistola.suite.api.v1.shared

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.fonts.model.Font
import java.time.OffsetDateTime

/**
 * Public, read-only REST view of a font family.
 *
 * Deliberately read-only: like assets, font binaries are managed through the
 * UI / catalog exchange only — there is no create/update/delete over REST. The
 * DTO carries the family metadata plus the list of present variant wire names
 * (`regular` / `bold` / `italic` / `bold_italic`).
 *
 * Hand-written (not generated from `epistola-contract`): the published contract
 * artifact has no Fonts API surface, so this controller + DTO are local to the
 * suite. Mirrors `CodeListDto`'s field shape (slug / catalog / catalogType /
 * readOnly / timestamps).
 */
data class FontDto(
    val slug: String,
    val name: String,
    val kind: String,
    val catalog: String,
    val catalogType: String,
    val readOnly: Boolean,
    val variants: List<String>,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

data class FontListResponse(
    val items: List<FontDto>,
)

internal fun Font.toDto(variants: List<String>) = FontDto(
    slug = slug.value,
    name = name,
    kind = kind.wire,
    catalog = catalogKey.value,
    // `catalogType` is loaded via the JOIN in `ListFonts`, so for any row that
    // exists it's non-null. AUTHORED is the safe fallback for the orphan case.
    catalogType = when (catalogType) {
        CatalogType.SUBSCRIBED -> "SUBSCRIBED"
        else -> "AUTHORED"
    },
    readOnly = catalogType == CatalogType.SUBSCRIBED,
    variants = variants,
    createdAt = createdAt,
    lastModified = updatedAt,
)
