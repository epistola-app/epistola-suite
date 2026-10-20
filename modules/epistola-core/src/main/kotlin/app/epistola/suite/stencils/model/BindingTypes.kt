// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

/**
 * The type a stencil parameter binding produces, where it is certain, and whether it fits the type
 * the parameter declares (stencil-parameters.md).
 *
 * Certain means a literal (`'Acme'`, `42`, `true`, `[]`), or a plain path into the template's data
 * whose every step the data contract types (`customer.name`). Anything else — a function, an
 * operator, a path through an array, a field the contract does not describe, a loop item — is
 * unknown and judged by nothing, so a mismatch is only reported when it is one.
 */
object BindingTypes {

    /** Blocks whose content sees a loop item the data contract does not type. */
    val LOOP_NODE_TYPES: Set<String> = setOf("loop", "datalist", "datatable")

    /** A JSON Schema type, with the item type of an array when it is known. */
    data class Type(val name: String, val items: Type? = null) {
        val label: String get() = when {
            name == "array" && items != null -> "a list of ${items.name}s"
            name == "array" -> "a list"
            name == "integer" -> "an integer"
            name == "object" -> "an object"
            else -> "a $name"
        }
    }

    private val PATH = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")
    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    /** Scopes a template binding may read that are not its data. */
    private val NOT_DATA = setOf("params", "sys")

    /** What [expression] produces against [dataModel], or null when that is not certain. */
    fun infer(expression: String, dataModel: Map<String, Any?>): Type? {
        val text = expression.trim()
        return when {
            text.length >= 2 && (text.startsWith("'") && text.endsWith("'") || text.startsWith("\"") && text.endsWith("\"")) ->
                Type("string").takeIf { text.drop(1).dropLast(1).none { it == text.first() } }

            text == "true" || text == "false" -> Type("boolean")

            NUMBER.matches(text) -> if (text.any { it == '.' || it == 'e' || it == 'E' }) Type("number") else Type("integer")

            text == "[]" -> Type("array")

            PATH.matches(text) && text.substringBefore('.') !in NOT_DATA -> pathType(text.split('.'), dataModel)

            else -> null
        }
    }

    /** The type the parameter's schema [property] declares, or null when it declares none. */
    fun declared(property: Any?): Type? = schemaType(property as? Map<*, *>)

    /** Whether a value of type [found] fits a parameter declared [wanted]. */
    fun fits(found: Type, wanted: Type): Boolean = when (wanted.name) {
        // An integer is a number; a number is not certainly a whole one, so it is not judged.
        "number" -> found.name == "number" || found.name == "integer"

        "integer" -> found.name == "integer" || found.name == "number"

        "array" -> found.name == "array" && (found.items == null || wanted.items == null || fits(found.items, wanted.items))

        else -> found.name == wanted.name
    }

    private fun pathType(segments: List<String>, dataModel: Map<String, Any?>): Type? {
        var schema: Map<*, *> = dataModel
        for (segment in segments) {
            if (schemaType(schema)?.name != "object") return null
            schema = (schema["properties"] as? Map<*, *>)?.get(segment) as? Map<*, *> ?: return null
        }
        return schemaType(schema)
    }

    private fun schemaType(schema: Map<*, *>?): Type? {
        if (schema == null) return null
        val name = when (val type = schema["type"]) {
            is String -> type

            // A nullable field (`["string", "null"]`) has the type it has when it is there.
            is List<*> -> type.filterIsInstance<String>().filter { it != "null" }.singleOrNull()

            else -> if (schema["properties"] is Map<*, *>) "object" else null
        } ?: return null
        val items = if (name == "array") schemaType(schema["items"] as? Map<*, *>) else null
        return Type(name, items)
    }
}
