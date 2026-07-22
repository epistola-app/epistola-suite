package app.epistola.suite.mcp.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Description of one editor component type the AI can use when designing or
 * referencing templates. Mirrors the static component registry shipped by
 * `app.epistola.contract:epistola-model` at
 * `/META-INF/epistola-model/component-registry.json`.
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
    /** Hand-curated TemplateDocument fragments showing realistic usage patterns. */
    val examples: List<ComponentExampleInfo> = emptyList(),
    /**
     * Optional parameter schema for this component type.
     * - absent from JSON: component has no parameter support
     * - `null` (JSON null): dynamic per-instance (e.g. stencil — call `get_stencil_version` for the schema)
     * - JSON Schema object: static parameters (same for every instance)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val parameters: tools.jackson.databind.JsonNode? = null,
)

/**
 * One concrete usage example for a component type. The fragment is a partial
 * TemplateDocument — its `nodes` and `slots` can be merged into a real document
 * and `rootNodeId` referenced as the insertion handle.
 */
data class ComponentExampleInfo(
    /** Stable identifier within the parent component, e.g. "minimal", "with-expression". */
    val name: String,
    /** One-line description of what this example demonstrates. */
    val description: String,
    val fragment: ComponentExampleFragmentInfo,
)

data class ComponentExampleFragmentInfo(
    /** Node id where the example starts (typically the component instance itself). */
    val rootNodeId: String,
    /** All nodes referenced by this fragment, including descendants. Untyped JSON. */
    val nodes: Map<String, Map<String, Any?>>,
    /** All slots referenced by nodes in this fragment. Untyped JSON. */
    val slots: Map<String, Map<String, Any?>>,
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
