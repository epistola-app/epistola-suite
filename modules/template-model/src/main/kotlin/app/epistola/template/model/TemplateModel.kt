package app.epistola.template.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Template model structure matching the frontend editor's Template type.
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
    val documentStyles: DocumentStyles = DocumentStyles(),
)

data class PageSettings(
    val format: PageFormat = PageFormat.A4,
    val orientation: Orientation = Orientation.Portrait,
    val margins: Margins = Margins(),
)

enum class PageFormat {
    A4,
    Letter,
    Custom,
}

enum class Orientation {
    @JsonProperty("portrait")
    Portrait,

    @JsonProperty("landscape")
    Landscape,
}

data class Margins(
    val top: Int = 20,
    val right: Int = 20,
    val bottom: Int = 20,
    val left: Int = 20,
)

data class DocumentStyles(
    val fontFamily: String? = null,
    val fontSize: String? = null,
    val fontWeight: String? = null,
    val color: String? = null,
    val lineHeight: String? = null,
    val letterSpacing: String? = null,
    val textAlign: TextAlign? = null,
    val backgroundColor: String? = null,
)

enum class TextAlign {
    @JsonProperty("left")
    Left,

    @JsonProperty("center")
    Center,

    @JsonProperty("right")
    Right,

    @JsonProperty("justify")
    Justify,
}

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

enum class BorderStyle {
    @JsonProperty("none")
    None,

    @JsonProperty("all")
    All,

    @JsonProperty("horizontal")
    Horizontal,

    @JsonProperty("vertical")
    Vertical,
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

/**
 * Expression language for template expressions.
 *
 * - JSONata: Concise syntax purpose-built for JSON transformation (recommended for most users)
 * - JavaScript: Full JS power for advanced use cases
 * - SimplePath: Lightweight path-only evaluation (fastest, no operations)
 */
enum class ExpressionLanguage {
    @JsonProperty("jsonata")
    Jsonata,

    @JsonProperty("javascript")
    JavaScript,

    @JsonProperty("simple_path")
    SimplePath,
}

/**
 * An expression that can be evaluated against input data.
 *
 * @param raw The expression string
 * @param language The expression language (defaults to JSONata)
 */
data class Expression(
    val raw: String,
    val language: ExpressionLanguage = ExpressionLanguage.Jsonata,
)
