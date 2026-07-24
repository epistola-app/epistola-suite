// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.validation.ValidationCode
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
     * on the first violation. JSONata syntax is checked for every binding
     * unconditionally; the schema-dependent checks (unknown keys, missing required) are
     * skipped for nodes whose schema cannot be resolved (e.g. legacy stencils without a
     * snapshot) — those flow through with whatever schema-shaped bindings were present.
     */
    @Suppress("UNCHECKED_CAST")
    fun validate(doc: TemplateDocument) {
        for (node in doc.nodes.values) {
            val rawBindings = node.props?.get(NodeParameterKeys.PROP_PARAMETER_BINDINGS) as? Map<*, *>

            // JSONata syntactic validity is independent of the parameter schema, so it
            // is checked for every binding — even on nodes whose schema can't be
            // resolved (legacy stencils without a snapshot, unregistered node types).
            rawBindings?.forEach { (rawKey, rawValue) ->
                val key = rawKey as? String ?: return@forEach
                // Blank / non-string values are not well-formed bindings; their shape is
                // PlaceholderValidator's job (NODE_PARAMETER_BINDING_EMPTY, runs first).
                // Skip them here so a blank required binding surfaces as
                // NODE_PARAMETER_BINDING_MISSING_REQUIRED rather than a misleading
                // syntax error.
                val expr = (rawValue as? String)?.trim()
                if (expr.isNullOrEmpty()) return@forEach
                try {
                    jsonata(expr)
                } catch (e: Exception) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$key",
                        "parameter binding '$key' expression is invalid — ${e.message}",
                        ValidationCode.NODE_PARAMETER_BINDING_SYNTAX_INVALID,
                    )
                }
            }

            // The remaining checks are schema-dependent — skip nodes whose schema
            // can't be resolved.
            val schema = schemaProvider.resolve(node, doc) ?: continue
            val properties = schema["properties"] as? Map<String, Any?> ?: continue
            val declaredNames = properties.keys

            // Unknown keys in bindings.
            rawBindings?.forEach { (rawKey, _) ->
                val key = rawKey as? String ?: return@forEach
                if (key !in declaredNames) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$key",
                        "parameter '$key' is not declared in the node's schema",
                        ValidationCode.NODE_PARAMETER_BINDING_UNKNOWN,
                    )
                }
            }

            // Required parameters with neither a binding nor a default.
            val required = (schema["required"] as? List<Any?>)?.filterIsInstance<String>().orEmpty()
            for (name in required) {
                // A present-but-blank value is not a usable binding — treat it as
                // absent so the precise MISSING_REQUIRED code wins over a syntax error.
                val hasBinding = (rawBindings?.get(name) as? String)?.isNotBlank() == true
                if (hasBinding) continue
                val prop = properties[name] as? Map<String, Any?>
                val hasDefault = prop?.containsKey("default") == true
                if (!hasDefault) {
                    throw ValidationException(
                        "content.${node.type}.props.parameterBindings.$name",
                        "required parameter '$name' has no binding and no default",
                        ValidationCode.NODE_PARAMETER_BINDING_MISSING_REQUIRED,
                    )
                }
            }
        }
    }
}
