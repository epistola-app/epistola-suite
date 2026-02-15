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

// ---------------------------------------------------------------------------
// V2 Template Document (normalized node/slot graph)
//
// These types mirror the TS `TemplateDocument` from the v2 editor and the
// JSON Schema in `schemas/template-document.schema.json`.
//
// Handwritten because the codegen tool cannot express:
//   - `Map<String, Node>` / `Map<String, Slot>` (produces empty inner classes)
//   - Sensible discriminated-union naming for ThemeRef (produces A/B)
// ---------------------------------------------------------------------------

/**
 * Root schema for an Epistola template document using the node/slot graph model.
 *
 * Unlike the V1 [TemplateModel] (recursive `blocks[]`), V2 stores a flat,
 * normalized graph of [Node]s and [Slot]s keyed by ID. The `root` field
 * points to the top-level node; traversal follows node → slot → children.
 */
data class TemplateDocument(
    val modelVersion: Int = 1,
    val root: String,
    val nodes: Map<String, Node>,
    val slots: Map<String, Slot>,
    val themeRef: ThemeRef = ThemeRef.Inherit,
    val pageSettingsOverride: PageSettings? = null,
    val documentStylesOverride: DocumentStyles? = null,
)

/**
 * A node in the document graph.
 *
 * @property id Unique node identifier (nanoid).
 * @property type Component type key (e.g. "text", "container", "columns", "root").
 * @property slots Ordered slot IDs owned by this node.
 * @property styles Inline CSS-like style overrides.
 * @property stylePreset Reference to a named preset in the theme's block style presets.
 * @property props Type-specific properties (content, expression, column config, etc.).
 */
data class Node(
    val id: String,
    val type: String,
    val slots: List<String> = emptyList(),
    val styles: Map<String, Any?>? = null,
    val stylePreset: String? = null,
    val props: Map<String, Any?>? = null,
)

/**
 * A slot that connects a parent node to its ordered children.
 *
 * @property id Unique slot identifier (nanoid).
 * @property nodeId Parent node that owns this slot.
 * @property name Semantic slot name (e.g. "children", "column-0", "cell-0-1", "body").
 * @property children Ordered child node IDs in this slot.
 */
data class Slot(
    val id: String,
    val nodeId: String,
    val name: String,
    val children: List<String> = emptyList(),
)

/**
 * Theme reference — either inherit from the parent cascade or override with a specific theme.
 *
 * JSON wire format uses a `type` discriminator:
 *   `{"type": "inherit"}` → [ThemeRef.Inherit]
 *   `{"type": "override", "themeId": "my-theme"}` → [ThemeRef.Override]
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ThemeRefInherit::class, name = "inherit"),
    JsonSubTypes.Type(value = ThemeRefOverride::class, name = "override"),
)
sealed class ThemeRef {
    companion object {
        /** Convenience constant for the inherit case. */
        val Inherit: ThemeRef = ThemeRefInherit()
    }
}

/** Inherit theme from the cascade (template default → tenant default). */
class ThemeRefInherit : ThemeRef() {
    override fun equals(other: Any?) = other is ThemeRefInherit
    override fun hashCode() = "inherit".hashCode()
    override fun toString() = "ThemeRef.Inherit"
}

/** Override with a specific theme by ID. */
data class ThemeRefOverride(val themeId: String) : ThemeRef()
