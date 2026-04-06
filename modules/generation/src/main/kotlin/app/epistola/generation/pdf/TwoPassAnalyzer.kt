package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument

/**
 * Analyzes a template document to determine whether two-pass rendering is required
 * and validates that two-pass expressions are not used in flow-affecting contexts.
 *
 * Two-pass rendering is needed when expressions reference values that are only known
 * after a complete render (e.g., total page count). These expressions are safe in body
 * text, headers, and footers, but must not appear in conditionals or loops where they
 * could change content volume and destabilize the page count between passes.
 *
 * To minimise layout instability the first (counting) pass injects a placeholder
 * value for `sys.pages.total` (see [DirectPdfRenderer.FIRST_PASS_PAGE_TOTAL_PLACEHOLDER]).
 * Because the placeholder is wider than or equal to most real totals, the actual value
 * in the second pass is unlikely to widen the text enough to push content to a new page.
 */
object TwoPassAnalyzer {

    /** Expression substrings that require two-pass rendering. */
    private val TWO_PASS_PATTERNS = listOf("sys.pages.total")

    /** Node types whose expressions affect content flow (conditionals, loops). */
    private val FLOW_AFFECTING_TYPES = setOf("conditional", "loop", "datatable")

    /** Prop keys that hold expression objects (Map with "raw" key). */
    private val EXPRESSION_PROP_KEYS = listOf("condition", "expression", "value")

    /**
     * Returns true if the document contains any expression that requires two-pass rendering.
     */
    fun requiresTwoPassRendering(document: TemplateDocument): Boolean = document.nodes.values.any { node -> collectExpressions(node.props).any { it.matchesTwoPass() } }

    /**
     * Validates that two-pass expressions are not used in flow-affecting node types.
     * @throws IllegalArgumentException if a conditional or loop references a two-pass expression.
     */
    fun validate(document: TemplateDocument) {
        for (node in document.nodes.values) {
            if (node.type !in FLOW_AFFECTING_TYPES) continue
            for (expr in collectExpressions(node.props)) {
                val pattern = expr.matchingTwoPassPattern()
                if (pattern != null) {
                    throw IllegalArgumentException(
                        "Expression '$expr' in ${node.type} node '${node.id}' references '$pattern', " +
                            "which is not allowed in ${node.type} nodes because it could destabilize page count between render passes. " +
                            "Use '$pattern' only in text, headers, or footers.",
                    )
                }
            }
        }
    }

    private fun String.matchesTwoPass(): Boolean = TWO_PASS_PATTERNS.any { this.contains(it) }

    private fun String.matchingTwoPassPattern(): String? = TWO_PASS_PATTERNS.firstOrNull { this.contains(it) }

    /**
     * Collects all expression strings from a node's props:
     * - Expression prop keys (condition, expression, value) with a "raw" string
     * - Inline expressions in TipTap content trees
     */
    private fun collectExpressions(props: Map<String, Any?>?): List<String> {
        if (props == null) return emptyList()
        val result = mutableListOf<String>()

        // Node-level expression props
        for (key in EXPRESSION_PROP_KEYS) {
            val raw = extractRaw(props[key])
            if (raw != null) result.add(raw)
        }

        // Inline expressions in TipTap content
        val content = props["content"]
        if (content is Map<*, *>) {
            collectTipTapExpressions(content, result)
        }

        return result
    }

    /** Extracts the "raw" string from an expression-shaped map. */
    private fun extractRaw(prop: Any?): String? {
        if (prop !is Map<*, *>) return null
        return prop["raw"] as? String
    }

    /** Recursively walks a TipTap content tree, collecting expression strings. */
    private fun collectTipTapExpressions(node: Map<*, *>, result: MutableList<String>) {
        if (node["type"] == "expression") {
            val attrs = node["attrs"] as? Map<*, *>
            val expr = attrs?.get("expression") as? String
            if (expr != null) result.add(expr)
        }

        val content = node["content"] as? List<*> ?: return
        for (child in content) {
            if (child is Map<*, *>) {
                collectTipTapExpressions(child, result)
            }
        }
    }
}
