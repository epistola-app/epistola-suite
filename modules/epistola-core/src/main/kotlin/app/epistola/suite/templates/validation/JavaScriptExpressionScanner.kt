// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.generation.expression.UnsupportedExpressionLanguages
import app.epistola.template.model.TemplateDocument

/**
 * Finds expressions written in JavaScript, which Epistola no longer evaluates.
 *
 * Expressions live in untyped node props, in three shapes the renderer reads a language from:
 * an expression object `{raw, language}` (conditions, loops, data lists, tables, QR codes), an
 * inline ProseMirror expression `{type: "expression", attrs: {expression, language}}`, and a
 * rich-text variable's props `{binding, language}`. The scan walks every prop value, so an
 * expression is found wherever it is nested.
 */
object JavaScriptExpressionScanner {
    private val EXPRESSION_KEYS = setOf("raw", "expression", "binding")

    /** Returns the path (`nodes.<id>.props.<…>`) of the first JavaScript expression, or null. */
    fun firstJavaScriptExpression(document: TemplateDocument): String? = javaScriptExpressionPaths(document).firstOrNull()

    /** Paths of every JavaScript expression in [document], in a deterministic order. */
    fun javaScriptExpressionPaths(document: TemplateDocument): List<String> {
        val found = mutableListOf<String>()
        for (nodeId in document.nodes.keys.sorted()) {
            val props = document.nodes.getValue(nodeId).props ?: continue
            scan(props, "nodes.$nodeId.props", found)
        }
        return found
    }

    private fun scan(value: Any?, path: String, found: MutableList<String>) {
        when (value) {
            is Map<*, *> -> {
                if (UnsupportedExpressionLanguages.isJavaScript(value["language"] as? String) &&
                    value.keys.any { it in EXPRESSION_KEYS }
                ) {
                    found += "$path.language"
                }
                for ((key, child) in value) scan(child, "$path.$key", found)
            }

            is List<*> -> value.forEachIndexed { index, child -> scan(child, "$path[$index]", found) }

            else -> Unit
        }
    }
}
