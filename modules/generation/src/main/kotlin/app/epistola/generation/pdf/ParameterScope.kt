// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Node

/**
 * Pushes parameter scope onto the render context for any node renderer that
 * supports parameters. Component-agnostic — stencils, future "snippet" or
 * "fragment" types share the same plumbing.
 *
 * Steps:
 *   1. Read `parameterBindings` (`Map<paramName, JSONata expression>`) and
 *      `paramsAlias` (default `"params"`) from the node's props.
 *   2. Evaluate each binding against the *outer* context. Apply schema
 *      defaults for unbound parameters.
 *   3. Return a new context with `parameterScopes[alias] = evaluatedMap`.
 *      Other aliases are preserved verbatim, so an outer scope (e.g.
 *      aliased `letter`) stays visible inside a nested node aliased `params`.
 *      Aliases that collide intentionally shadow.
 *
 * Returns the outer context unchanged when [schema] is null (the node has no
 * parameters declared) or has no `properties`. Render mode is honoured for
 * required-without-binding-and-no-default: STRICT throws; PREVIEW substitutes
 * a `<paramName>` placeholder so canvas previews surface the missing value
 * visually instead of silently rendering empty.
 *
 * The schema is a JSON-structured `Map<String, Any?>` — same shape Jackson
 * deserializes JSON Schema into. Avoids pulling Jackson into the generation
 * module and works directly off snapshot data carried in node props.
 */
object ParameterScope {

    private const val PROP_PARAMETER_BINDINGS = "parameterBindings"
    private const val PROP_PARAMS_ALIAS = "paramsAlias"
    private const val DEFAULT_ALIAS = "params"

    fun push(node: Node, schema: Map<String, Any?>?, outer: RenderContext): RenderContext {
        if (schema == null) return outer
        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as? Map<String, Any?> ?: return outer

        val alias = (node.props?.get(PROP_PARAMS_ALIAS) as? String)?.takeIf { it.isNotBlank() } ?: DEFAULT_ALIAS

        @Suppress("UNCHECKED_CAST")
        val rawBindings = (node.props?.get(PROP_PARAMETER_BINDINGS) as? Map<String, Any?>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<Any?>)?.filterIsInstance<String>()?.toSet().orEmpty()

        val evaluated = mutableMapOf<String, Any?>()
        for ((name, propSchemaRaw) in properties) {
            @Suppress("UNCHECKED_CAST")
            val propSchema = propSchemaRaw as? Map<String, Any?>
            val expr = rawBindings[name] as? String
            evaluated[name] = if (expr != null && expr.isNotBlank()) {
                outer.expressionEvaluator.evaluate(
                    raw = expr,
                    language = ExpressionLanguage.jsonata,
                    data = outer.effectiveData,
                    loopContext = outer.loopContext,
                )
            } else {
                val defaultValue = propSchema?.get("default")
                when {
                    defaultValue != null -> defaultValue
                    name !in required -> null
                    outer.renderMode == RenderMode.STRICT ->
                        error("Required parameter '$name' has no binding and no default")
                    // PREVIEW: visible placeholder so the preview pane mirrors the
                    // editor canvas (which renders `<paramName>` from the editor's
                    // own scope provider). Without this, PREVIEW would silently
                    // substitute null and the user wouldn't notice the missing
                    // binding until the strict render at delivery time.
                    else -> "<$name>"
                }
            }
        }

        return outer.copy(
            parameterScopes = outer.parameterScopes + (alias to evaluated),
        )
    }
}
