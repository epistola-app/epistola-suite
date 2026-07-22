package app.epistola.suite.documents.queries

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Projects a generation payload onto a fixed set of schema paths and produces a stable
 * string key of the values found there.
 *
 * Used by [CheckRecentUsageCompatibility] to deduplicate recent payloads before validating
 * them against a changed contract. A payload that was valid under the old schema can only
 * *newly* fail at a path the schema diff touched, so two payloads that agree on every affected
 * path validate identically — we need to run the validator on only one representative per
 * distinct projection.
 *
 * The projection is deliberately biased towards **over-splitting**: when in doubt it treats
 * payloads as distinct rather than merging them, so the dedup can never hide a real regression.
 *
 * Paths follow the [SchemaCompatibilityChecker][app.epistola.suite.templates.contracts.SchemaCompatibilityChecker]
 * dotted form, using `[]` to descend into array elements, e.g. `customer.name`, `items[].sku`,
 * `matrix[][].value`.
 *
 * This is a stateless utility — instantiate directly, no Spring injection needed.
 */
class RecentUsageShapeProjector {

    /**
     * Builds a canonical key describing the values [data] holds at each of [paths].
     * Absent values are rendered as `∅`; array descents render as `[…]` over their elements.
     */
    fun key(data: ObjectNode, paths: List<String>): String {
        val sb = StringBuilder()
        for (path in paths) {
            sb.append(path).append('=')
            collect(data, tokenize(path), 0, sb)
            sb.append(';')
        }
        return sb.toString()
    }

    private fun collect(node: JsonNode?, tokens: List<String>, index: Int, out: StringBuilder) {
        if (node == null || node.isMissingNode) {
            out.append(ABSENT)
            return
        }
        if (index == tokens.size) {
            out.append(node.toString())
            return
        }
        when (val token = tokens[index]) {
            ARRAY -> {
                if (!node.isArray) {
                    out.append(ABSENT)
                    return
                }
                out.append('[')
                for (element in node) {
                    collect(element, tokens, index + 1, out)
                    out.append(',')
                }
                out.append(']')
            }
            else -> collect((node as? ObjectNode)?.get(token), tokens, index + 1, out)
        }
    }

    /**
     * Splits a dotted path into field/array tokens, e.g. `items[].sku` → `["items", "[]", "sku"]`
     * and `matrix[][].value` → `["matrix", "[]", "[]", "value"]`.
     */
    private fun tokenize(path: String): List<String> {
        val tokens = mutableListOf<String>()
        for (segment in path.split('.')) {
            var name = segment
            var arrayDepth = 0
            while (name.endsWith("[]")) {
                arrayDepth++
                name = name.dropLast(2)
            }
            if (name.isNotEmpty()) tokens.add(name)
            repeat(arrayDepth) { tokens.add(ARRAY) }
        }
        return tokens
    }

    private companion object {
        const val ARRAY = "[]"
        const val ABSENT = "∅"
    }
}
