package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import com.dashjoin.jsonata.Jsonata.jsonata
import org.springframework.stereotype.Component

/**
 * Cross-document validator for the `parameterBindings` prop carried by parametrised
 * nodes. For every node with bindings, looks up the node's declared parameter schema
 * via [NodeParameterSchemaProviderRegistry], then verifies:
 *  - every binding key references a declared parameter (`NODE_PARAMETER_BINDING_UNKNOWN`);
 *  - every required parameter has a binding (or a `default` declared on its schema)
 *    (`NODE_PARAMETER_BINDING_MISSING_REQUIRED`);
 *  - every binding expression is syntactically valid JSONata
 *    (`NODE_PARAMETER_BINDING_SYNTAX_INVALID`).
 *
 * Structural shape is checked separately by
 * [PlaceholderValidator.validateStencilBindingShape] and runs first; this validator
 * assumes the bindings map is already well-formed.
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
    @Suppress("UNCHECKED_CAST")
    fun validate(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            val rawBindings = node.props?.get(NodeParameterKeys.PROP_PARAMETER_BINDINGS) as? Map<*, *>
            val schema = schemaProvider.resolve(node, doc) ?: continue
            val properties = schema["properties"] as? Map<String, Any?> ?: continue
            val declaredNames = properties.keys

            // Unknown keys in bindings + JSONata syntax check.
            rawBindings?.forEach { (rawKey, rawValue) ->
                val key = rawKey as? String ?: return@forEach
                if (key !in declaredNames) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$key",
                        "NODE_PARAMETER_BINDING_UNKNOWN: parameter '$key' is not declared in the node's schema",
                    )
                }
                val expr = (rawValue as? String)?.trim() ?: return@forEach
                try {
                    jsonata(expr)
                } catch (e: Exception) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$key",
                        "NODE_PARAMETER_BINDING_SYNTAX_INVALID: parameter binding '$key' expression is invalid — ${e.message}",
                    )
                }
            }

            // Required parameters with neither a binding nor a default.
            val required = (schema["required"] as? List<Any?>)?.filterIsInstance<String>().orEmpty()
            for (name in required) {
                val hasBinding = rawBindings?.containsKey(name) == true
                if (hasBinding) continue
                val prop = properties[name] as? Map<String, Any?>
                val hasDefault = prop?.containsKey("default") == true
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
