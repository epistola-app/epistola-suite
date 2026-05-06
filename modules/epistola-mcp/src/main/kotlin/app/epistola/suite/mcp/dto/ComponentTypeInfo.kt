package app.epistola.suite.mcp.dto

/**
 * Description of one editor component type the AI can use when designing or
 * referencing templates. Mirrors the serializable shape of `ComponentDefinition`
 * in `modules/editor/src/main/typescript/engine/registry.ts` — the JSON file
 * at `/META-INF/resources/editor/component-registry.json` (built by
 * `pnpm dump-registry` in the editor module) is the canonical source.
 */
data class ComponentTypeInfo(
    /** Component type discriminator used as `node.type` in template documents. */
    val type: String,
    /** Display label for the block palette / inspector. */
    val label: String,
    /** Lucide icon name; null if not set. */
    val icon: String?,
    /** "content", "layout", "logic", or "page". */
    val category: String,
    /** True for child-only components (e.g. datatable-column) — not insertable from the palette. */
    val hidden: Boolean,
    val slots: List<SlotTemplateInfo>,
    val allowedChildren: AllowedChildrenInfo,
    /** "all" or a list of style property keys this component supports. */
    val applicableStyles: ApplicableStyles,
    val inspector: List<InspectorFieldInfo>,
    val defaultStyles: Map<String, Any?>?,
    val defaultProps: Map<String, Any?>?,
    /** Singleton-style guard (e.g. `pageheader` allows max 1 instance). */
    val maxInstancesPerDocument: Int?,
)

data class SlotTemplateInfo(
    val name: String,
    /** When true the slot is repeated based on props (e.g. one slot per column). */
    val dynamic: Boolean = false,
)

/**
 * Tagged union: which child types the component accepts.
 * - `mode='all'`: any type allowed
 * - `mode='none'`: leaf component, no children
 * - `mode='allowlist'`: only the listed types
 * - `mode='denylist'`: any type except the listed ones
 */
data class AllowedChildrenInfo(
    val mode: String,
    val types: List<String>? = null,
)

/**
 * Either the literal string `"all"` (every style key applies) or a list of
 * specific style keys. Modeled as a sealed wrapper to keep the JSON shape
 * stable on the wire while letting consumers branch in code.
 */
sealed class ApplicableStyles {
    data object All : ApplicableStyles()
    data class Subset(val keys: List<String>) : ApplicableStyles()
}

data class InspectorFieldInfo(
    val key: String,
    val label: String,
    /** "text", "number", "boolean", "select", "expression", "json", "color", or "unit". */
    val type: String,
    val options: List<InspectorOptionInfo>? = null,
    val defaultValue: Any? = null,
    /** For type='unit', the available units (e.g. ["pt", "sp", "%"]). */
    val units: List<String>? = null,
)

data class InspectorOptionInfo(
    val label: String,
    val value: Any?,
)
