package app.epistola.suite.mcp.tools

import app.epistola.suite.mcp.dto.ComponentTypeInfo
import app.epistola.suite.mcp.support.ComponentRegistryProvider
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class ComponentMcpTools(
    private val registry: ComponentRegistryProvider,
) {

    @McpTool(
        name = "list_component_types",
        description = "List all component types the editor supports — the building blocks of a template's " +
            "node/slot graph. Each entry includes the type discriminator, display label, category " +
            "(content/layout/logic/page), slot templates, allowedChildren rules, applicable style keys, " +
            "inspector fields, default props/styles, and usage examples. Use this to design templates " +
            "from scratch or to validate that a node type referenced in template content is supported. " +
            "Components flagged `hidden=true` are child-only (e.g. datatable-column) and cannot be " +
            "inserted at the top level. " +
            "The `parameters` field carries the component's parameter schema when present: " +
            "- a JSON Schema object means static parameters (same for every instance); " +
            "- `null` means dynamic per-instance parameters (e.g. stencil — use `get_stencil_version` " +
            "to fetch the schema for a specific version); " +
            "- absent means the component has no parameter support.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listComponentTypes(): List<ComponentTypeInfo> = registry.components

    @McpTool(
        name = "get_component_type",
        description = "Fetch the full descriptor for a single component type. Equivalent to filtering " +
            "`list_component_types` by `type`, but cheaper for single lookups when the AI only needs " +
            "one type's slots or props. See `list_component_types` for how the `parameters` field works.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getComponentType(
        @McpToolParam(description = "Component type discriminator, e.g. 'text', 'container', 'datatable'.")
        type: String,
    ): ComponentTypeInfo? = registry.get(type)
}
