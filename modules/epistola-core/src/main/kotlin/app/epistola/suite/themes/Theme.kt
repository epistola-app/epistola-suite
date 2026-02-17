package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import org.jdbi.v3.json.Json
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import java.time.OffsetDateTime

/**
 * A named block style preset (like a CSS class for blocks).
 *
 * @param label Human-readable label for the preset
 * @param styles CSS-like style properties for this preset
 * @param applicableTo Node types this preset can be applied to (empty/null means all types)
 */
data class BlockStylePreset(
    val label: String,
    val styles: Map<String, Any>,
    val applicableTo: List<String>? = null,
)

/**
 * Wrapper type for a map of [BlockStylePreset] to work around Java type erasure.
 *
 * When Jackson deserializes a generic `Map<String, BlockStylePreset>` from JSON, type erasure
 * causes it to produce `Map<String, LinkedHashMap>` instead. This wrapper class provides
 * explicit type information that Jackson can use for proper deserialization.
 *
 * The database stores this as a JSON object `{"presetName": {...}, ...}`, and this class
 * serializes/deserializes directly from/to that format using custom serializers.
 *
 * @see app.epistola.suite.templates.model.DataExamples for the same pattern applied to lists
 */
@JsonDeserialize(using = BlockStylePresetsDeserializer::class)
@JsonSerialize(using = BlockStylePresetsSerializer::class)
data class BlockStylePresets(
    private val presets: Map<String, BlockStylePreset> = emptyMap(),
) : Map<String, BlockStylePreset> by presets {
    companion object {
        val EMPTY = BlockStylePresets(emptyMap())

        fun of(vararg pairs: Pair<String, BlockStylePreset>) = BlockStylePresets(mapOf(*pairs))
    }
}

class BlockStylePresetsDeserializer : ValueDeserializer<BlockStylePresets>() {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): BlockStylePresets {
        val mapType = ctxt.typeFactory.constructMapType(
            Map::class.java,
            String::class.java,
            BlockStylePreset::class.java,
        )
        val items: Map<String, BlockStylePreset> = ctxt.readValue(p, mapType)
        return BlockStylePresets(items)
    }
}

class BlockStylePresetsSerializer : ValueSerializer<BlockStylePresets>() {
    override fun serialize(
        value: BlockStylePresets,
        gen: JsonGenerator,
        ctxt: SerializationContext,
    ) {
        gen.writeStartObject()
        for ((key, preset) in value) {
            gen.writeName(key)
            ctxt.writeValue(gen, preset)
        }
        gen.writeEndObject()
    }
}

/**
 * A theme defines reusable styling that can be applied across multiple templates.
 *
 * Themes provide:
 * - Document-level styles (font, color, alignment defaults)
 * - Optional page settings (format, orientation, margins)
 * - Named block style presets (like CSS classes for blocks)
 *
 * Templates reference a theme via themeRef in TemplateDocument. Style cascade order:
 * 1. Theme document styles (lowest priority)
 * 2. Template document styles (override theme)
 * 3. Theme block preset (when block has stylePreset)
 * 4. Block inline styles (highest priority)
 */
data class Theme(
    val id: ThemeId,
    val tenantId: TenantId,
    val name: String,
    val description: String?,
    @Json val documentStyles: DocumentStyles,
    @Json val pageSettings: PageSettings?,
    @Json val blockStylePresets: BlockStylePresets?,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
