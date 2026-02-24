package app.epistola.suite.api.v1.shared

import app.epistola.api.model.BlockStylePresetDto
import app.epistola.api.model.MarginsDto
import app.epistola.api.model.PageSettingsDto
import app.epistola.api.model.ThemeDto
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.Margins
import app.epistola.suite.templates.model.Orientation
import app.epistola.suite.templates.model.PageFormat
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePreset
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.Theme
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

internal fun Theme.toDto(objectMapper: ObjectMapper) = ThemeDto(
    id = id.value,
    tenantId = tenantId.value,
    name = name,
    description = description,
    documentStyles = objectMapper.valueToTree(documentStyles),
    pageSettings = pageSettings?.toDto(),
    blockStylePresets = blockStylePresets?.mapValues { (_, value) ->
        BlockStylePresetDto(
            label = value.label,
            styles = objectMapper.valueToTree(value.styles),
            applicableTo = value.applicableTo,
        )
    },
    createdAt = createdAt,
    lastModified = lastModified,
)

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
    Orientation.portrait -> PageSettingsDto.Orientation.PORTRAIT
    Orientation.landscape -> PageSettingsDto.Orientation.LANDSCAPE
}

internal fun Margins.toDto() = MarginsDto(
    top = top.toInt(),
    right = right.toInt(),
    bottom = bottom.toInt(),
    left = left.toInt(),
)

// From DTO to domain

internal fun ObjectNode?.toDomainDocumentStyles(objectMapper: ObjectMapper): DocumentStyles {
    if (this == null) return emptyMap()
    return objectMapper.convertValue(this, DocumentStyles::class.java)
}

internal fun PageSettingsDto.toDomain() = PageSettings(
    format = format?.toDomain() ?: PageFormat.A4,
    orientation = orientation?.toDomain() ?: Orientation.portrait,
    margins = margins?.toDomain() ?: Margins(top = 20, right = 20, bottom = 20, left = 20),
)

internal fun PageSettingsDto.Format.toDomain(): PageFormat = when (this) {
    PageSettingsDto.Format.A4 -> PageFormat.A4
    PageSettingsDto.Format.LETTER -> PageFormat.Letter
    PageSettingsDto.Format.CUSTOM -> PageFormat.Custom
}

internal fun PageSettingsDto.Orientation.toDomain(): Orientation = when (this) {
    PageSettingsDto.Orientation.PORTRAIT -> Orientation.portrait
    PageSettingsDto.Orientation.LANDSCAPE -> Orientation.landscape
}

internal fun MarginsDto.toDomain() = Margins(
    top = top?.toLong() ?: 20L,
    right = right?.toLong() ?: 20L,
    bottom = bottom?.toLong() ?: 20L,
    left = left?.toLong() ?: 20L,
)

// Helper to convert BlockStylePresetDto map to domain BlockStylePresets
internal fun Map<String, BlockStylePresetDto>?.toDomainPresets(objectMapper: ObjectMapper): BlockStylePresets? = this?.let {
    BlockStylePresets(
        it.mapValues { (_, value) ->
            BlockStylePreset(
                label = value.label,
                styles = objectMapper.convertValue(value.styles, Map::class.java) as Map<String, Any>,
                applicableTo = value.applicableTo,
            )
        },
    )
}
