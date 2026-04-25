package app.epistola.suite.templates.analysis

import app.epistola.template.model.TemplateDocument
import org.springframework.stereotype.Component

/**
 * Extracts all data contract variable paths referenced by a template document.
 *
 * Walks the node/slot graph depth-first, maintaining a loop scope stack to resolve
 * loop item aliases (e.g., `order.name` inside a loop on `orders`) back to root-level
 * contract paths (e.g., `orders[*].name`).
 *
 * Handles:
 * - Loop/datalist/datatable expressions with `itemAlias` scoping
 * - Conditional expressions
 * - QR code value expressions
 * - Inline expressions in TipTap text content
 * - Nested loops (compounding scope)
 * - JSONata functions (extracts identifiers, filters out `$func` names)
 */
@Component
class TemplatePathExtractor {

    private data class LoopScope(val alias: String, val rootPath: String)

    /**
     * Extracts all referenced data paths from a template document.
     * Paths are resolved to the root data context (loop aliases expanded).
     *
     * @return Set of root-level paths like `["customer.name", "orders[*].items[*].price"]`
     */
    fun extractReferencedPaths(document: TemplateDocument): Set<String> {
        val paths = mutableSetOf<String>()
        val rootNode = document.nodes[document.root] ?: return paths
        visitNode(rootNode, document, emptyList(), paths)
        return paths
    }

    private fun visitNode(
        node: app.epistola.template.model.Node,
        document: TemplateDocument,
        scopeStack: List<LoopScope>,
        paths: MutableSet<String>,
    ) {
        val props = node.props ?: emptyMap()
        val updatedScope = when (node.type) {
            "loop", "datalist", "datatable" -> {
                val exprRaw = extractExpressionRaw(props["expression"])
                if (exprRaw != null) {
                    val resolvedExpr = resolveToRoot(exprRaw, scopeStack)
                    paths.add(resolvedExpr)
                    val alias = props["itemAlias"] as? String ?: "item"
                    scopeStack + LoopScope(alias, "$resolvedExpr[*]")
                } else {
                    scopeStack
                }
            }
            else -> {
                // Conditional
                extractExpressionRaw(props["condition"])?.let { raw ->
                    extractPathsFromExpression(raw, scopeStack, paths)
                }
                // QR code
                extractExpressionRaw(props["value"])?.let { raw ->
                    extractPathsFromExpression(raw, scopeStack, paths)
                }
                // Text content (TipTap JSON)
                extractPathsFromTipTapContent(props["content"], scopeStack, paths)
                scopeStack
            }
        }

        // Visit child slots → child nodes
        for (slotId in node.slots) {
            val slot = document.slots[slotId] ?: continue
            for (childId in slot.children) {
                val childNode = document.nodes[childId] ?: continue
                visitNode(childNode, document, updatedScope, paths)
            }
        }
    }

    /**
     * Extracts the raw expression string from an expression prop.
     * Props are stored as `{raw: "...", language: "jsonata"}` maps.
     */
    private fun extractExpressionRaw(prop: Any?): String? {
        if (prop == null) return null
        @Suppress("UNCHECKED_CAST")
        val map = prop as? Map<String, Any?> ?: return null
        val raw = map["raw"] as? String ?: return null
        return raw.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts variable paths from an expression string (best-effort).
     * Handles JSONata, JavaScript, and simple_path expressions.
     */
    private fun extractPathsFromExpression(expression: String, scopeStack: List<LoopScope>, paths: MutableSet<String>) {
        for (identifier in extractIdentifiers(expression)) {
            if (shouldExclude(identifier, scopeStack)) continue
            paths.add(resolveToRoot(identifier, scopeStack))
        }
    }

    /**
     * Walks TipTap JSON content to find inline expression nodes.
     * TipTap content structure: `{type: "doc", content: [{type: "paragraph", content: [...]}]}`
     * Expression nodes: `{type: "expression", attrs: {expression: "customer.name"}}`
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractPathsFromTipTapContent(content: Any?, scopeStack: List<LoopScope>, paths: MutableSet<String>) {
        if (content == null) return
        val map = content as? Map<String, Any?> ?: return

        // Check if this is an expression node
        if (map["type"] == "expression") {
            val attrs = map["attrs"] as? Map<String, Any?> ?: return
            val expression = attrs["expression"] as? String ?: return
            if (expression.isNotBlank()) {
                extractPathsFromExpression(expression, scopeStack, paths)
            }
            return
        }

        // Recurse into content array
        val children = map["content"] as? List<Any?> ?: return
        for (child in children) {
            extractPathsFromTipTapContent(child, scopeStack, paths)
        }
    }

    /**
     * Resolves a path by replacing loop alias prefixes with their root-level paths.
     *
     * Example: `resolveToRoot("order.name", [{alias: "order", rootPath: "orders[*]"}])`
     *   → `"orders[*].name"`
     */
    private fun resolveToRoot(path: String, scopeStack: List<LoopScope>): String {
        // Check from innermost scope outward
        for (scope in scopeStack.asReversed()) {
            if (path == scope.alias) {
                return scope.rootPath
            }
            if (path.startsWith("${scope.alias}.")) {
                val rest = path.removePrefix("${scope.alias}.")
                return "${scope.rootPath}.$rest"
            }
        }
        return path
    }

    /**
     * Extracts identifier-like paths from an expression string.
     * Matches dot-separated identifier sequences: `customer.name`, `items.price`
     * Strips string literals first to avoid matching identifiers inside quotes.
     */
    private fun extractIdentifiers(expression: String): List<String> {
        // Remove string literals (single and double quoted) to avoid false matches
        val stripped = expression
            .replace(Regex("""'[^']*'"""), "")
            .replace(Regex(""""[^"]*""""), "")

        // Match sequences of identifiers separated by dots
        val pattern = Regex("""(?<!\$)\b([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)*)""")
        return pattern.findAll(stripped)
            .map { it.groupValues[1] }
            .filter { it !in KEYWORDS }
            .toList()
    }

    /**
     * Determines if an identifier should be excluded from contract paths.
     */
    private fun shouldExclude(identifier: String, scopeStack: List<LoopScope>): Boolean {
        // System variables
        if (identifier.startsWith("sys.") || identifier == "sys") return true

        // Loop auto-variables: item_index, item_first, item_last
        for (scope in scopeStack) {
            if (identifier == "${scope.alias}_index") return true
            if (identifier == "${scope.alias}_first") return true
            if (identifier == "${scope.alias}_last") return true
        }

        // Pure keywords (already filtered by extractIdentifiers, but double-check)
        if (identifier in KEYWORDS) return true

        return false
    }

    companion object {
        private val KEYWORDS = setOf(
            "true", "false", "null", "undefined",
            "and", "or", "not", "in",
            "if", "else", "then",
            "var", "let", "const", "function", "return",
            "this",
        )
    }
}
