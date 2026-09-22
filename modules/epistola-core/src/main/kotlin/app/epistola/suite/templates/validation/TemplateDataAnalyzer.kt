// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * A field the data does not supply.
 *
 * @property path JSON Pointer into the data (`/customer/address`). This is the outermost absent node:
 *   a missing `customer` object is one entry, not one per leaf.
 * @property required Whether the contract requires it. An optional field is only reported when the
 *   template reads it (see [TemplateDataAnalyzer]).
 * @property schema The contract's schema for the field, with local `$ref`s inlined
 */
data class MissingDataField(
    val path: String,
    val required: Boolean,
    val schema: ObjectNode,
)

/**
 * A supplied value that breaks the contract.
 *
 * @property path JSON Pointer into the data the failing keyword was evaluated against
 * @property keyword The JSON Schema keyword that failed (`type`, `enum`, `minimum`, …)
 * @property message Human-readable message from the validator
 * @property schema The contract's schema at [path], with local `$ref`s inlined; null when the
 *   location has no schema of its own (an unknown property, a rich-text internal node)
 */
data class InvalidDataField(
    val path: String,
    val keyword: String,
    val message: String,
    val schema: ObjectNode?,
)

/**
 * How template data measures up against the template's data contract.
 *
 * @property valid Whether the data passes the contract. Absent *optional* fields do not make it invalid.
 * @property missingFields Absent fields, in schema declaration order
 * @property invalidFields Supplied values that break the contract
 * @property missingDataSchema The contract cut down to the missing fields, as one schema a form
 *   generator can take as-is. Fields inside array items are left out of it, because a schema cannot
 *   address one array element; they are still listed in [missingFields]. Null when nothing is missing.
 */
data class TemplateDataAnalysis(
    val valid: Boolean,
    val missingFields: List<MissingDataField>,
    val invalidFields: List<InvalidDataField>,
    val missingDataSchema: ObjectNode?,
) {
    companion object {
        /** A template without a contract accepts any data. */
        val NO_CONTRACT = TemplateDataAnalysis(valid = true, missingFields = emptyList(), invalidFields = emptyList(), missingDataSchema = null)
    }
}

/**
 * Works out which fields template data is missing or has wrong, so a caller can ask for exactly those.
 *
 * Not tied to one use: preview is the only caller today, generation could use the same analysis.
 *
 * - **Required** fields that are absent are always reported.
 * - **Optional** fields that are absent are reported only when the template reads them — a path in
 *   [referencedPaths][analyze] starts with the field's path — because those change what the document
 *   shows. Optional fields the template never reads are left out as noise.
 *
 * Validity itself comes from [JsonSchemaValidator]; this class adds the structure around it. Missing
 * fields are found by walking the contract in declaration order, so the list is stable between calls;
 * a `required` failure the walk cannot see (one declared under `if`/`then`) is appended after it.
 */
@Component
class TemplateDataAnalyzer(
    private val schemaValidator: JsonSchemaValidator,
    private val objectMapper: ObjectMapper,
) {

    /**
     * @param contract The data contract's JSON Schema
     * @param data The data after [JsonSchemaValidator.applyDefaults], so a defaulted field is not missing
     * @param referencedPaths Data paths the template reads, as `TemplatePathExtractor` reports them
     *   (`customer.name`, `orders[*].price`)
     */
    fun analyze(contract: ObjectNode, data: ObjectNode, referencedPaths: Set<String>): TemplateDataAnalysis {
        val violations = schemaValidator.validateDetailed(contract, data)
        val schemas = ContractSchemas(contract)
        val referenced = referencedPaths.map(::parseReferencedPath)

        val missing = LinkedHashMap<String, MissingDataField>()
        collectMissing(schemas, contract, data, "", emptyList(), referenced, missing)

        violations
            .filter { it.keyword == "required" && !it.conditional && it.property != null }
            .map { "${it.pointer}/${escapePointerToken(it.property!!)}" }
            .filter { pointer -> missing.keys.none { pointer == it || pointer.startsWith("$it/") } }
            .forEach { pointer ->
                missing[pointer] = MissingDataField(pointer, required = true, schema = schemas.schemaAt(pointer) ?: objectMapper.createObjectNode())
            }

        val invalid = violations
            .filterNot { it.keyword == "required" && !it.conditional }
            .distinctBy { Triple(it.pointer, it.keyword, it.message) }
            .map { InvalidDataField(it.pointer, it.keyword, it.message, schemas.schemaAt(it.pointer)) }

        val missingFields = missing.values.toList()
        return TemplateDataAnalysis(
            valid = violations.isEmpty(),
            missingFields = missingFields,
            invalidFields = invalid,
            missingDataSchema = if (missingFields.isEmpty()) null else buildMissingDataSchema(schemas, contract, missingFields),
        )
    }

    private fun collectMissing(
        schemas: ContractSchemas,
        schema: ObjectNode,
        data: ObjectNode,
        pointer: String,
        schemaPath: List<String>,
        referenced: List<List<String>>,
        missing: MutableMap<String, MissingDataField>,
    ) {
        val required = schemas.requiredOf(schema)
        for ((name, propertySchema) in schemas.propertiesOf(schema)) {
            val childPointer = "$pointer/${escapePointerToken(name)}"
            val childPath = schemaPath + name
            when (val value = data.get(name)) {
                null -> {
                    val isRequired = name in required
                    if (isRequired || referenced.any { it.startsWith(childPath) }) {
                        missing[childPointer] = MissingDataField(childPointer, isRequired, schemas.inline(propertySchema))
                    }
                }
                is ObjectNode -> collectMissing(schemas, propertySchema, value, childPointer, childPath, referenced, missing)
                is ArrayNode -> schemas.itemsOf(propertySchema)?.let { items ->
                    value.forEachIndexed { index, element ->
                        if (element is ObjectNode) {
                            collectMissing(schemas, items, element, "$childPointer/$index", childPath + ARRAY_ITEM, referenced, missing)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun buildMissingDataSchema(schemas: ContractSchemas, contract: ObjectNode, missingFields: List<MissingDataField>): ObjectNode {
        val out = objectMapper.createObjectNode()
        contract.get("\$schema")?.let { out.set("\$schema", it.deepCopy()) }
        out.put("type", "object")

        for (field in missingFields) {
            val tokens = parsePointer(field.path)
            var schema: ObjectNode = contract
            var target = out
            // Walk object properties only: a field inside an array item cannot be addressed by a schema.
            val steps = tokens.map { token ->
                val property = schemas.propertiesOf(schema)[token] ?: return@map null
                schema = property
                token to property
            }
            if (steps.any { it == null }) continue

            steps.filterNotNull().forEachIndexed { index, (token, property) ->
                val properties = target.get("properties") as? ObjectNode ?: target.putObject("properties")
                if (field.required) addRequired(target, token)
                if (index == steps.lastIndex) {
                    properties.set(token, field.schema.deepCopy())
                } else {
                    val next = properties.get(token) as? ObjectNode ?: properties.putObject(token).also { node ->
                        val resolved = schemas.deref(property)
                        resolved.get("title")?.let { node.set("title", it.deepCopy()) }
                        resolved.get("description")?.let { node.set("description", it.deepCopy()) }
                        node.put("type", "object")
                    }
                    target = next
                }
            }
        }

        // A recursive local `$ref` is left in place by inlining; keep what it points at resolvable.
        if (containsLocalRef(out)) {
            for (keyword in listOf("\$defs", "definitions")) {
                contract.get(keyword)?.let { out.set(keyword, it.deepCopy()) }
            }
        }
        return out
    }

    private fun addRequired(schema: ObjectNode, name: String) {
        val required = schema.get("required") as? ArrayNode ?: schema.putArray("required")
        if (required.none { it.isString && it.asString() == name }) required.add(name)
    }

    private fun containsLocalRef(node: JsonNode): Boolean = when (node) {
        is ObjectNode -> node.properties().any { (key, value) ->
            (key == "\$ref" && value.isString && value.asString().startsWith("#")) || containsLocalRef(value)
        }
        is ArrayNode -> node.any(::containsLocalRef)
        else -> false
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix

    companion object {
        /** Path segment standing for "any element" of an array, as in `orders[*].price`. */
        private const val ARRAY_ITEM = "[*]"

        /** `orders[*].items[0].price` → `[orders, [*], items, [*], price]`. An index is treated as any element. */
        internal fun parseReferencedPath(path: String): List<String> = path.split('.').flatMap { segment ->
            val name = segment.substringBefore('[')
            val brackets = segment.count { it == '[' }
            listOfNotNull(name.takeIf { it.isNotEmpty() }) + List(brackets) { ARRAY_ITEM }
        }

        internal fun escapePointerToken(token: String): String = token.replace("~", "~0").replace("/", "~1")

        internal fun parsePointer(pointer: String): List<String> = if (pointer.isEmpty()) {
            emptyList()
        } else {
            pointer.removePrefix("/").split('/').map { it.replace("~1", "/").replace("~0", "~") }
        }
    }
}

/**
 * Read access to a contract schema that follows its local `$ref`s and `allOf` members, which is how
 * a data location's effective schema is assembled. External `$ref`s — the preloaded rich-text
 * schemas — are left as they are: their canonical URL already tells a client what the field is.
 */
internal class ContractSchemas(private val root: ObjectNode) {

    /** Follows a chain of local `$ref`s to the schema it ends at. */
    fun deref(schema: ObjectNode): ObjectNode {
        var current = schema
        val seen = mutableSetOf<String>()
        while (true) {
            val ref = localRef(current) ?: return current
            if (!seen.add(ref)) return current
            current = target(ref) ?: return current
        }
    }

    /** Declared properties, in declaration order, including those of `allOf` members. */
    fun propertiesOf(schema: ObjectNode): Map<String, ObjectNode> {
        val properties = LinkedHashMap<String, ObjectNode>()
        for (member in withAllOf(schema)) {
            (member.get("properties") as? ObjectNode)?.properties()?.forEach { (name, node) ->
                if (node is ObjectNode) properties.putIfAbsent(name, node)
            }
        }
        return properties
    }

    fun requiredOf(schema: ObjectNode): Set<String> = withAllOf(schema).flatMap { member ->
        (member.get("required") as? ArrayNode)?.filter { it.isString }?.map { it.asString() }.orEmpty()
    }.toSet()

    fun itemsOf(schema: ObjectNode): ObjectNode? = withAllOf(schema).firstNotNullOfOrNull { it.get("items") as? ObjectNode }

    /** The schema at a data JSON Pointer, with local `$ref`s inlined, or null when no schema describes it. */
    fun schemaAt(pointer: String): ObjectNode? {
        var current: ObjectNode = root
        for (token in TemplateDataAnalyzer.parsePointer(pointer)) {
            current = propertiesOf(current)[token]
                ?: token.toIntOrNull()?.let { index -> itemAt(current, index) }
                ?: return null
        }
        return inline(current)
    }

    private fun itemAt(schema: ObjectNode, index: Int): ObjectNode? = withAllOf(schema).firstNotNullOfOrNull { member ->
        (member.get("prefixItems") as? ArrayNode)?.get(index) as? ObjectNode
            ?: when (val items = member.get("items")) {
                is ObjectNode -> items
                is ArrayNode -> items.get(index) as? ObjectNode
                else -> null
            }
    }

    /** A copy of [schema] with every local `$ref` replaced by its target; a recursive one stays a `$ref`. */
    fun inline(schema: ObjectNode): ObjectNode = inline(schema, emptySet()) as ObjectNode

    private fun inline(node: JsonNode, resolving: Set<String>): JsonNode = when (node) {
        is ObjectNode -> {
            val ref = localRef(node)
            val target = ref?.takeIf { it !in resolving }?.let(::target)
            if (ref != null && target != null) {
                // The target's keywords, then any sibling annotations (`title`, `description`) the
                // author put next to the `$ref`.
                val merged = inline(target, resolving + ref) as ObjectNode
                node.properties().filter { (key, _) -> key != "\$ref" }.forEach { (key, value) ->
                    merged.set(key, inline(value, resolving))
                }
                merged
            } else {
                node.deepCopy().also { copy ->
                    node.properties().forEach { (key, value) ->
                        if (key != "\$ref") copy.set(key, inline(value, resolving))
                    }
                }
            }
        }
        is ArrayNode -> node.deepCopy().also { copy ->
            node.forEachIndexed { index, value -> copy.set(index, inline(value, resolving)) }
        }
        else -> node
    }

    private fun withAllOf(schema: ObjectNode, visiting: Set<ObjectNode> = emptySet()): List<ObjectNode> {
        val resolved = deref(schema)
        // Identity, not equality: a `$ref` cycle through `allOf` revisits the very same node.
        if (visiting.any { it === resolved }) return emptyList()
        val members = (resolved.get("allOf") as? ArrayNode)?.filterIsInstance<ObjectNode>().orEmpty()
        return listOf(resolved) + members.flatMap { withAllOf(it, visiting + setOf(resolved)) }
    }

    private fun localRef(schema: ObjectNode): String? = schema.get("\$ref")?.takeIf { it.isString }?.asString()?.takeIf { it.startsWith("#") }

    private fun target(ref: String): ObjectNode? = root.at(ref.removePrefix("#")) as? ObjectNode
}
