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
// Template Document (normalized node/slot graph)
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
 * Stores a flat, normalized graph of [Node]s and [Slot]s keyed by ID.
 * The `root` field points to the top-level node; traversal follows
 * node -> slot -> children.
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
