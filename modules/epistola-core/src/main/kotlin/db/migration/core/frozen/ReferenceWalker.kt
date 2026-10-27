// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Where content names another resource, as the 2.0 upgrade finds it (ADR 0033, ADR 0035), frozen:
 * `ResourceReferenceSites` of 2.0, a `stencil` or `image` node's props, a `themeRef`, a `fontFamily`,
 * each naming the catalog alias it means in `catalogKey`, or none for its own catalog. And how a
 * document is written in the released form a release keeps (`CatalogContentBuilder.Outbound` of 2.0):
 * a reference into its own catalog relative, no `namespace` or `registry` on any reference.
 *
 * `fixtures/references/v1.json` and `fixtures/storage/v1.json` pin it (`ReferenceWalkerGoldenVectorsTest`,
 * `FrozenToolkitGoldenVectorsTest`).
 */
object ReferenceWalker {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = -2064647702

    /** One reference a content holds: the catalog alias it names (null for its own), type and key. */
    data class Reference(val alias: String?, val type: String, val key: String)

    private data class Site(val type: String, val keyField: String, val nodeType: String? = null, val container: String? = null)

    private val sites = listOf(
        Site("stencil", "stencilKey", nodeType = "stencil"),
        Site("image", "imageKey", nodeType = "image"),
        Site("theme", "themeKey", container = "themeRef"),
        Site("font", "key", container = "fontFamily"),
    )

    /** Every reference in [node], at any depth. */
    fun references(node: JsonNode): Set<Reference> = buildSet { collect(node, this) }

    /**
     * A template variant's [model], of a template of [catalogKey], as a release holds it. A reference
     * into its own catalog loses its `catalogKey`: a `themeRef` (a typed member of the document) keeps
     * it as null, as the suite writes a document; a node's props and a style (free-form maps) leave it
     * out. Images are never qualified, so they keep theirs.
     */
    fun releasedModel(model: JsonNode, catalogKey: String): JsonNode = model.deepCopy().also { relativize(it, catalogKey, keepThemeRefKey = true) }

    /**
     * A stencil's [document], of a stencil of [catalogKey], as a release holds it: as [releasedModel],
     * but a `themeRef` into its own catalog loses its `catalogKey` too.
     */
    fun releasedForm(document: JsonNode, catalogKey: String): JsonNode = document.deepCopy().also { relativize(it, catalogKey, keepThemeRefKey = false) }

    private fun holderOf(node: ObjectNode, site: Site): ObjectNode? {
        val nodeType = node.get("type")?.takeIf { it.isString }?.asString()
        val holder = if (site.nodeType != null) node.get("props").takeIf { nodeType == site.nodeType } else node.get(site.container!!)
        return (holder as? ObjectNode)?.takeIf { it.text(site.keyField) != null }
    }

    private fun collect(node: JsonNode, into: MutableSet<Reference>) {
        if (node is ObjectNode) {
            for (site in sites) {
                val holder = holderOf(node, site) ?: continue
                into += Reference(holder.text("catalogKey"), site.type, holder.text(site.keyField)!!)
            }
            node.properties().forEach { (_, child) -> collect(child, into) }
        } else if (node is ArrayNode) {
            node.forEach { collect(it, into) }
        }
    }

    private fun relativize(node: JsonNode, catalogKey: String, keepThemeRefKey: Boolean) {
        if (node is ObjectNode) {
            for (site in sites) {
                val holder = holderOf(node, site) ?: continue
                holder.remove("namespace")
                holder.remove("registry")
                if (site.type == "image" || holder.text("catalogKey") != catalogKey) continue
                if (keepThemeRefKey && site.container == "themeRef") holder.putNull("catalogKey") else holder.remove("catalogKey")
            }
            node.properties().forEach { (_, child) -> relativize(child, catalogKey, keepThemeRefKey) }
        } else if (node is ArrayNode) {
            node.forEach { relativize(it, catalogKey, keepThemeRefKey) }
        }
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
}
