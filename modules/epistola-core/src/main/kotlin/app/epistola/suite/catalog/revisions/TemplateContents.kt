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
 *   [{ key, attributes, isDefault }] } }`, everything the wire form says of the template and of each
 *   variant's place in it, and no model.
 * - **A variant** (a row of its own, [VariantRows], kind [VARIANT_KIND]): `{ "resource": { title,
 *   templateModel, metadata, description } }`, its model whole (the default variant's included,
 *   which the wire form keeps on the template). A variant content stored before contract
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
            put("title", variant.title)
            model?.let { set("templateModel", objectMapper.valueToTree(it)) }
            variant.metadata?.takeIf { it.isNotEmpty() }?.let { set("metadata", objectMapper.valueToTree(it)) }
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
        resource.set("templateModel", defaultModel ?: objectMapper.nullNode())
        resource.set(
            "variants",
            objectMapper.createArrayNode().also { list ->
                slots.forEach { slot ->
                    val key = slot.path("key").asString()
                    val part = parts[key]?.path("resource")
                    list.add(
                        objectMapper.createObjectNode().apply {
                            put("key", key)
                            set("title", part?.get("title") ?: objectMapper.nullNode())
                            set("attributes", slot.path("attributes"))
                            set("templateModel", if (key == default?.path("key")?.asString()) objectMapper.nullNode() else part?.get("templateModel") ?: objectMapper.nullNode())
                            put("isDefault", slot.path("isDefault").asBoolean(false))
                            part?.get("metadata")?.let { set("metadata", it) }
                            part?.get("description")?.let { set("description", it) }
                        },
                    )
                }
            },
        )
        return resource
    }
}
