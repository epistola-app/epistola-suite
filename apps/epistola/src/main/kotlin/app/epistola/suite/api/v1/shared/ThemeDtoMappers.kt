package app.epistola.suite.api.v1.shared

import app.epistola.api.model.DocumentStylesDto
import app.epistola.api.model.MarginsDto
import app.epistola.api.model.PageSettingsDto
import app.epistola.api.model.ThemeDto
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.Margins
import app.epistola.suite.templates.model.Orientation
import app.epistola.suite.templates.model.PageFormat
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.templates.model.TextAlign
import app.epistola.suite.themes.Theme

internal fun Theme.toDto() = ThemeDto(
    id = id.value,
    tenantId = tenantId.value,
    name = name,
    description = description,
    documentStyles = documentStyles.toDto(),
    pageSettings = pageSettings?.toDto(),
    blockStylePresets = blockStylePresets,
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun DocumentStyles.toDto() = DocumentStylesDto(
    fontFamily = fontFamily,
    fontSize = fontSize,
    fontWeight = fontWeight,
    color = color,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    textAlign = textAlign?.toDto(),
    backgroundColor = backgroundColor,
)

internal fun TextAlign.toDto(): DocumentStylesDto.TextAlign = when (this) {
    TextAlign.Left -> DocumentStylesDto.TextAlign.LEFT
    TextAlign.Center -> DocumentStylesDto.TextAlign.CENTER
    TextAlign.Right -> DocumentStylesDto.TextAlign.RIGHT
    TextAlign.Justify -> DocumentStylesDto.TextAlign.JUSTIFY
}

internal fun PageSettings.toDto() = PageSettingsDto(
    format = format.toDto(),
    orientation = orientation.toDto(),
    margins = margins.toDto(),
)

internal fun PageFormat.toDto(): PageSettingsDto.Format = when (this) {
    PageFormat.A4 -> PageSettingsDto.Format.A4
    PageFormat.Letter -> PageSettingsDto.Format.LETTER
    PageFormat.Custom -> PageSettingsDto.Format.CUSTOM
}

internal fun Orientation.toDto(): PageSettingsDto.Orientation = when (this) {
    Orientation.Portrait -> PageSettingsDto.Orientation.PORTRAIT
    Orientation.Landscape -> PageSettingsDto.Orientation.LANDSCAPE
}

internal fun Margins.toDto() = MarginsDto(
    top = top,
    right = right,
    bottom = bottom,
    left = left,
)

// From DTO to domain

internal fun DocumentStylesDto?.toDomain() = DocumentStyles(
    fontFamily = this?.fontFamily,
    fontSize = this?.fontSize,
    fontWeight = this?.fontWeight,
    color = this?.color,
    lineHeight = this?.lineHeight,
    letterSpacing = this?.letterSpacing,
    textAlign = this?.textAlign?.toDomain(),
    backgroundColor = this?.backgroundColor,
)

internal fun DocumentStylesDto.TextAlign.toDomain(): TextAlign = when (this) {
    DocumentStylesDto.TextAlign.LEFT -> TextAlign.Left
    DocumentStylesDto.TextAlign.CENTER -> TextAlign.Center
    DocumentStylesDto.TextAlign.RIGHT -> TextAlign.Right
    DocumentStylesDto.TextAlign.JUSTIFY -> TextAlign.Justify
}

internal fun PageSettingsDto.toDomain() = PageSettings(
    format = format?.toDomain() ?: PageFormat.A4,
    orientation = orientation?.toDomain() ?: Orientation.Portrait,
    margins = margins?.toDomain() ?: Margins(),
)

internal fun PageSettingsDto.Format.toDomain(): PageFormat = when (this) {
    PageSettingsDto.Format.A4 -> PageFormat.A4
    PageSettingsDto.Format.LETTER -> PageFormat.Letter
    PageSettingsDto.Format.CUSTOM -> PageFormat.Custom
}

internal fun PageSettingsDto.Orientation.toDomain(): Orientation = when (this) {
    PageSettingsDto.Orientation.PORTRAIT -> Orientation.Portrait
    PageSettingsDto.Orientation.LANDSCAPE -> Orientation.Landscape
}

internal fun MarginsDto.toDomain() = Margins(
    top = top ?: 20,
    right = right ?: 20,
    bottom = bottom ?: 20,
    left = left ?: 20,
)
