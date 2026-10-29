// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.VariantEntry
import app.epistola.template.model.TemplateDocument
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * How a template is stored (ADR 0033, step 7b): one content for the template and one per variant,
 * a draft's and a release's alike, so they share them. Not the wire form: a release's
 * `TemplateResource` is assembled from them ([assemble]), and that is what is exported and
 * fingerprinted.
 *
 * - **The template** (its row, kind `template`): `{ "resource": { key, name, …, variants:
 *   [{ key, attributes, isDefault }], metadata } }`, everything the wire form says of the template and
 *   of each variant's place in it, its producer metadata included, and no model.
 * - **A variant** (a row of its own, [VariantRows], kind [VARIANT_KIND]): `{ "resource": { title,
 *   templateModel, description } }`, its model whole (the default variant's included,
 *   which the wire form keeps on the template). Its name is stored as `title`, as it was before wire
 *   v8 named it `name` (contract `2.0.0-alpha.4`), so stored contents keep their hashes. A variant content stored before contract
 *   `2.0.0-alpha.2` may keep its description beside the resource; a release holding one was
 *   fingerprinted without it.
 *
 * Saving a variant writes its one content; the template's does not change.
 */
object TemplateContents {
    /** The content kind of a variant, as its row's storage type ([VariantRows.TYPE]). */
    const val VARIANT_KIND = VariantRows.TYPE

    /** The template's content: the wire form of the template without its models. */
    fun head(resource: TemplateResource, objectMapper: ObjectMapper): ObjectNode {
        val node = objectMapper.valueToTree<ObjectNode>(resource)
        node.remove("templateModel")
        node.set(
            "variants",
            objectMapper.createArrayNode().also { list ->
                resource.variants.forEach { variant ->
                    list.add(
                        objectMapper.createObjectNode().apply {
                            put("key", variant.key)
                            set("attributes", objectMapper.valueToTree(variant.attributes))
                            put("isDefault", variant.isDefault)
                        },
                    )
                }
            },
        )
        return objectMapper.createObjectNode().set("resource", node)
    }

    /**
     * [variant]'s content, its model [model] (the template's for the default variant); null for a
     * variant that has none of its own and renders the default's, as a received release may say.
     */
    fun part(variant: VariantEntry, model: TemplateDocument?, objectMapper: ObjectMapper): ObjectNode {
        val resource = objectMapper.createObjectNode().apply {
            put("title", variant.name)
            model?.let { set("templateModel", objectMapper.valueToTree(it)) }
            variant.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }
        return objectMapper.createObjectNode().set("resource", resource)
    }

    /**
     * The `TemplateResource` of a template's [head] content and its variants' [parts] (by key): the
     * wire form, with the default variant's model on the template and none on its entry. A variant
     * without a part renders the template's model.
     */
    fun assemble(head: JsonNode, parts: Map<String, JsonNode>, objectMapper: ObjectMapper): JsonNode {
        val resource = StoredContent.resourceOf(head).deepCopy() as ObjectNode
        val slots = resource.path("variants").toList()
        val default = slots.firstOrNull { it.path("isDefault").asBoolean(false) } ?: slots.firstOrNull()
        val defaultModel = default?.let { parts[it.path("key").asString()] }?.path("resource")?.path("templateModel")
        // An absent member is left out, never written as null: the wire-v8 schemas refuse a null
        // inside a resource (contract `2.0.0-alpha.4`).
        if (defaultModel != null && !defaultModel.isMissingNode && !defaultModel.isNull) resource.set("templateModel", defaultModel) else resource.remove("templateModel")
        resource.set(
            "variants",
            objectMapper.createArrayNode().also { list ->
                slots.forEach { slot ->
                    val key = slot.path("key").asString()
                    val part = parts[key]?.path("resource")
                    list.add(
                        objectMapper.createObjectNode().apply {
                            put("key", key)
                            part?.get("title")?.takeUnless { it.isNull }?.let { set("name", it) }
                            set("attributes", slot.path("attributes"))
                            if (key != default?.path("key")?.asString()) part?.get("templateModel")?.takeUnless { it.isNull }?.let { set("templateModel", it) }
                            put("isDefault", slot.path("isDefault").asBoolean(false))
                            part?.get("description")?.let { set("description", it) }
                        },
                    )
                }
            },
        )
        foldVariantMetadata(resource, slots.associate { it.path("key").asString() to parts[it.path("key").asString()]?.path("resource")?.get("metadata") }, objectMapper)
        return resource
    }

    /**
     * Folds what a variant content stored before contract `2.0.0-alpha.3` holds as its own `metadata`
     * into the template's, as the contract's migrator folds an early wire-v8 variant's:
     * `{"<namespace>": V}` of variant `k` becomes `{"<namespace>": {"variants": {"k": V}}}` on the
     * template. A release retained before then keeps such contents, and assembles to the wire form
     * that says the same. A value the template already holds is never overwritten.
     */
    private fun foldVariantMetadata(resource: ObjectNode, byVariant: Map<String, JsonNode?>, objectMapper: ObjectMapper) {
        val held = byVariant.filterValues { it != null && it.isObject && !it.isEmpty }
        if (held.isEmpty()) return
        val metadata = (resource.get("metadata") as? ObjectNode) ?: objectMapper.createObjectNode()
        held.forEach { (variant, values) ->
            values!!.properties().forEach { (namespace, value) ->
                val space = metadata.get(namespace)?.let { it as? ObjectNode ?: return@forEach } ?: metadata.putObject(namespace)
                val variants = space.get("variants")?.let { it as? ObjectNode ?: return@forEach } ?: space.putObject("variants")
                if (!variants.has(variant)) variants.set(variant, value)
            }
        }
        resource.set("metadata", metadata)
    }
}
