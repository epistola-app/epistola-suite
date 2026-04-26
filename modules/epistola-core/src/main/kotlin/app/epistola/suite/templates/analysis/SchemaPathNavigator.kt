package app.epistola.suite.templates.analysis

import tools.jackson.databind.node.ObjectNode

/**
 * Resolves dot-notation paths (e.g., `orders[*].items[*].price`) against a
 * JSON Schema tree to determine field type and existence.
 *
 * Stateless utility — instantiate directly, no Spring injection needed.
 */
class SchemaPathNavigator {

    data class ResolvedField(
        val type: String,
        val found: Boolean,
    )

    /**
     * Resolves a path against a JSON Schema, returning the field's type.
     *
     * Handles:
     * - Simple paths: `customer.name`
     * - Nested objects: `customer.address.city`
     * - Array items: `orders[*].total` (descends through `items`)
     * - Nested arrays: `orders[*].items[*].price`
     *
     * @return [ResolvedField] with the type and whether the path was found
     */
    fun resolve(schema: ObjectNode, path: String): ResolvedField {
        val segments = parsePath(path)
        if (segments.isEmpty()) return ResolvedField(type = "unknown", found = false)

        var current: tools.jackson.databind.JsonNode = schema

        for ((name, isArrayItem) in segments) {
            // Navigate into properties
            val properties = current.get("properties")
            if (properties != null && properties.has(name)) {
                current = properties.get(name)
            } else {
                return ResolvedField(type = "unknown", found = false)
            }

            // If this segment has [*], descend into array items
            if (isArrayItem) {
                val items = current.get("items")
                if (items != null) {
                    current = items
                } else {
                    return ResolvedField(type = "unknown", found = false)
                }
            }
        }

        val type = current.get("type")?.asText() ?: "unknown"
        return ResolvedField(type = type, found = true)
    }

    private data class PathSegment(val name: String, val isArrayItem: Boolean)

    /**
     * Parses a path like `orders[*].items[*].price` into segments.
     * `orders[*]` → PathSegment("orders", isArrayItem=true)
     * `price` → PathSegment("price", isArrayItem=false)
     */
    private fun parsePath(path: String): List<PathSegment> {
        if (path.isBlank()) return emptyList()
        return path.split(".").map { segment ->
            if (segment.endsWith("[*]")) {
                PathSegment(segment.removeSuffix("[*]"), isArrayItem = true)
            } else {
                PathSegment(segment, isArrayItem = false)
            }
        }
    }
}
