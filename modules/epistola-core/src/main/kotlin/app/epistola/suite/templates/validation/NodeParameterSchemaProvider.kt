package app.epistola.suite.templates.validation

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Resolves the JSON Schema describing a node's parameters.
 *
 * Component-agnostic by design: the *generic* `parameterBindings` prop is shared
 * across all parametrised node types, but the *source* of the schema differs:
 *  - **Static-parametrised components** (e.g. a hypothetical "snippet"): schema
 *    is a constant declared by the component type. Implementations return the
 *    same schema for every instance.
 *  - **Dynamic components** (today: stencil): each instance carries its own
 *    schema as a snapshot prop on the node, copied from the source version at
 *    insert/upgrade time. Implementations read that snapshot.
 *
 * The schema is returned as a JSON-structured `Map<String, Any?>` — Jackson's
 * natural deserialization shape — so the render module (which has no Jackson
 * dependency) can consume the result directly.
 *
 * Concrete providers register as Spring beans returning a [NodeTypeProviderBinding];
 * the [NodeParameterSchemaProviderRegistry] dispatches by node type.
 */
fun interface NodeParameterSchemaProvider {
    /** Returns the schema for the given node, or null if the node has no parameters. */
    fun resolve(node: Node, document: TemplateDocument): Map<String, Any?>?
}

/**
 * Spring-bean registration mapping a node type to its provider. Beans of this
 * type are picked up by [NodeParameterSchemaProviderRegistry].
 */
data class NodeTypeProviderBinding(
    val nodeType: String,
    val provider: NodeParameterSchemaProvider,
)

/**
 * Dispatches schema resolution to the provider registered for the node's type.
 * Returns null when no provider is registered (the node type has no parameter
 * support) or when the provider yields null (no parameters on this instance).
 */
@Component
class NodeParameterSchemaProviderRegistry(
    bindings: List<NodeTypeProviderBinding>,
) {
    private val byType: Map<String, NodeParameterSchemaProvider> = bindings.associate { it.nodeType to it.provider }

    fun resolve(node: Node, document: TemplateDocument): Map<String, Any?>? {
        val provider = byType[node.type] ?: return null
        return provider.resolve(node, document)
    }
}
