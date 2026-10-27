// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.text.Normalizer

/**
 * A data example's key, as wire v8 requires one and 1.x kept none (contract `CatalogKeys` of 2.0),
 * frozen. Golden vectors in `fixtures/storage/v1.json` pin it, and check that the contract derives
 * the same keys (`FrozenToolkitGoldenVectorsTest`).
 */
object DataExampleKeys {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = 1108981719

    private val mapper = CanonicalJson.mapper

    /**
     * A data example's key from its name: the name without accents, lowercased, every run of other
     * characters one hyphen, trimmed, cut to 50 characters without a trailing hyphen, padded with `0`
     * to 3, `example` when nothing is left; the first free suffix `-2`, `-3`, … when taken.
     */
    fun dataExampleKey(name: String, taken: Set<String>): String {
        val decomposed = Regex("\\p{M}+").replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
        val base = fit(Regex("[^a-z0-9]+").replace(decomposed.lowercase(), "-").trim('-'), 50).ifEmpty { "example" }.padEnd(3, '0')
        if (base !in taken) return base
        var suffix = 2
        while (true) {
            val candidate = fit(base, 50 - "-$suffix".length).ifEmpty { "example".take(50 - "-$suffix".length) } + "-$suffix"
            if (candidate !in taken) return candidate
            suffix++
        }
    }

    /**
     * A 1.x data contract's examples as wire v8 keeps them: `name`, `data` and `key`. A named example
     * without a key gets one from its name first ([keyedByName]), then what is still without one is
     * keyed in the wire form ([wireForm]), as the upgrade did in two steps.
     */
    fun examples(json: JsonNode?): ArrayNode = wireForm(keyedByName(json))

    /** [json] with a key derived for each example that has a name and no key, in their order, the keys already there taken first. */
    fun keyedByName(json: JsonNode?): JsonNode? {
        val examples = json as? ArrayNode ?: return json
        val copy = examples.deepCopy()
        val objects = copy.filterIsInstance<ObjectNode>()
        val taken = objects.mapNotNullTo(mutableSetOf()) { it.get("key")?.takeIf { key -> key.isString }?.asString() }
        for (example in objects) {
            if (example.has("key")) continue
            val name = example.get("name")?.takeIf { it.isString }?.asString() ?: continue
            example.put("key", dataExampleKey(name, taken).also { taken += it })
        }
        return copy
    }

    /** The examples in the wire form, `name` (`Unnamed` when it has none), `data` and `key`, each keyed: one without a key from its name. */
    fun wireForm(json: JsonNode?): ArrayNode {
        val examples = json?.takeIf { it is ArrayNode }?.toList().orEmpty()
        val taken = examples.mapNotNull { it.text("key") }.toMutableSet()
        return mapper.createArrayNode().also { out ->
            examples.forEach { example ->
                val name = example.text("name") ?: "Unnamed"
                val key = example.text("key") ?: dataExampleKey(name, taken).also { taken += it }
                out.add(
                    mapper.createObjectNode().apply {
                        put("name", name)
                        set("data", example.get("data") ?: mapper.createObjectNode())
                        put("key", key)
                    },
                )
            }
        }
    }

    private fun fit(value: String, length: Int): String = if (value.length <= length) value else value.take(length).trimEnd('-')

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
}
