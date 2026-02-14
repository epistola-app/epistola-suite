package app.epistola.template.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Document-level style overrides as an open map.
 * The style-registry drives which properties are available — this type
 * does not constrain them at the Kotlin level.
 *
 * Defined manually because the JSON Schema `{ "type": "object" }` with no
 * properties cannot be expressed by the codegen tool as a Map type.
 */
typealias DocumentStyles = Map<String, Any>

/**
 * An expression with a language identifier.
 *
 * Defined manually because the codegen tool cannot express default parameter values,
 * and existing stored templates omit `language` (defaulting to jsonata).
 */
data class Expression(
    val raw: String,
    val language: ExpressionLanguage = ExpressionLanguage.jsonata,
)

// ---------------------------------------------------------------------------
// V1 Template Model (block-based, recursive)
//
// These types have no JSON Schema — they use complex Jackson polymorphism
// annotations and will remain handwritten until the V1 editor is removed.
// ---------------------------------------------------------------------------

/**
 * Template model structure matching the V1 frontend editor's Template type.
 * This is the visual layout structure stored in the database as JSON.
 *
 * @param themeId Optional reference to a Theme entity. When set, the template inherits
 *                document-level styles and block style presets from the theme.
 */
data class TemplateModel(
    val id: String,
    val name: String,
    val version: Int = 1,
    val themeId: String? = null,
    val pageSettings: PageSettings,
    val blocks: List<Block> = emptyList(),
    val documentStyles: DocumentStyles = emptyMap(),
)

/**
 * Base class for all block types in a template.
 *
 * @property stylePreset Optional reference to a named preset in the theme's blockStylePresets.
 *                       Works like CSS classes - preset styles are applied first, then inline styles override.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = TextBlock::class, name = "text"),
    JsonSubTypes.Type(value = ContainerBlock::class, name = "container"),
    JsonSubTypes.Type(value = ConditionalBlock::class, name = "conditional"),
    JsonSubTypes.Type(value = LoopBlock::class, name = "loop"),
    JsonSubTypes.Type(value = ColumnsBlock::class, name = "columns"),
    JsonSubTypes.Type(value = TableBlock::class, name = "table"),
    JsonSubTypes.Type(value = PageBreakBlock::class, name = "pagebreak"),
    JsonSubTypes.Type(value = PageHeaderBlock::class, name = "pageheader"),
    JsonSubTypes.Type(value = PageFooterBlock::class, name = "pagefooter"),
)
sealed class Block {
    abstract val id: String
    abstract val type: String
    abstract val styles: Map<String, Any>?
    abstract val stylePreset: String?
}

data class TextBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val content: Map<String, Any>? = null, // TipTap JSONContent
) : Block() {
    override val type: String = "text"
}

data class ContainerBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val children: List<Block> = emptyList(),
) : Block() {
    override val type: String = "container"
}

data class ConditionalBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val condition: Expression,
    val inverse: Boolean? = null,
    val children: List<Block> = emptyList(),
) : Block() {
    override val type: String = "conditional"
}

data class LoopBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val expression: Expression,
    val itemAlias: String,
    val indexAlias: String? = null,
    val children: List<Block> = emptyList(),
) : Block() {
    override val type: String = "loop"
}

data class ColumnsBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val columns: List<Column> = emptyList(),
    val gap: Int? = null,
) : Block() {
    override val type: String = "columns"
}

data class Column(
    val id: String,
    val size: Int = 1,
    val children: List<Block> = emptyList(),
)

data class TableBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val rows: List<TableRow> = emptyList(),
    val columnWidths: List<Int>? = null,
    val borderStyle: BorderStyle? = null,
) : Block() {
    override val type: String = "table"
}

data class TableRow(
    val id: String,
    val cells: List<TableCell> = emptyList(),
    val isHeader: Boolean? = null,
)

data class TableCell(
    val id: String,
    val children: List<Block> = emptyList(),
    val colspan: Int? = null,
    val rowspan: Int? = null,
    val styles: Map<String, Any>? = null,
)

data class PageBreakBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
) : Block() {
    override val type: String = "pagebreak"
}

data class PageHeaderBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val children: List<Block> = emptyList(),
) : Block() {
    override val type: String = "pageheader"
}

data class PageFooterBlock(
    override val id: String,
    override val styles: Map<String, Any>? = null,
    override val stylePreset: String? = null,
    val children: List<Block> = emptyList(),
) : Block() {
    override val type: String = "pagefooter"
}
