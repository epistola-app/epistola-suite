package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Cross-document validator for the `parameterBindings` prop carried by parametrised
 * nodes. For every node with bindings, looks up the node's declared parameter schema
 * via [NodeParameterSchemaProviderRegistry], then verifies:
 *  - every binding key references a declared parameter (`NODE_PARAMETER_BINDING_UNKNOWN`);
 *  - every required parameter has a binding (or a `default` declared on its schema)
 *    (`NODE_PARAMETER_BINDING_MISSING_REQUIRED`).
 *
 * Structural shape is checked separately by
 * [PlaceholderValidator.validateStencilBindingShape] and runs first; this validator
 * assumes the bindings map is already well-formed.
 *
 * JSONata syntax of binding values is not validated here in v1 — the editor
 * validates at edit time, and the renderer fails gracefully on bad expressions.
 * If we later want defence-in-depth, `com.dashjoin:jsonata` can be added as a
 * dependency and the parse called on each value.
 */
@Component
class NodeParameterBindingValidator(
    private val schemaProvider: NodeParameterSchemaProviderRegistry,
) {
    /**
     * Validates every parametrised node in the document. Throws [ValidationException]
     * on the first violation. No-op for nodes whose schema cannot be resolved (e.g.
     * legacy stencils without a snapshot) — those flow through with whatever bindings
     * were present.
     */
    fun validate(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            val rawBindings = node.props?.get(NodeParameterKeys.PROP_PARAMETER_BINDINGS) as? Map<*, *>
            val schema = schemaProvider.resolve(node, doc) as? ObjectNode ?: continue
            val properties = schema.get("properties") as? ObjectNode ?: continue
            val declaredNames = properties.propertyNames().toSet()

            // Unknown keys in bindings.
            rawBindings?.keys?.forEach { rawKey ->
                val key = rawKey as? String ?: return@forEach
                if (key !in declaredNames) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$key",
                        "NODE_PARAMETER_BINDING_UNKNOWN: parameter '$key' is not declared in the node's schema",
                    )
                }
            }

            // Required parameters with neither a binding nor a default.
            val required = (schema.get("required") as? ArrayNode)?.mapNotNull { it.asString() }?.toSet().orEmpty()
            for (name in required) {
                val hasBinding = rawBindings?.containsKey(name) == true
                if (hasBinding) continue
                val prop = properties.get(name) as? ObjectNode
                val hasDefault = prop?.get("default") != null
                if (!hasDefault) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$name",
                        "NODE_PARAMETER_BINDING_MISSING_REQUIRED: required parameter '$name' has no binding and no default",
                    )
                }
            }
        }
    }
}
