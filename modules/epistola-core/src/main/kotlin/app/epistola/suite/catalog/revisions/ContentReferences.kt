// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.graph.ResourceReferenceSites
import tools.jackson.databind.JsonNode

/**
 * One thing a content refers to (ADR 0033, `resource_references`): the [alias] of the catalog it
 * names, or null for the referring content's own catalog, and the resource's wire [type] and [key].
 */
data class ContentReference(val alias: String?, val type: String, val key: String)

/**
 * What a stored content refers to, read from its JSON once, when it is stored.
 *
 * The references embedded in documents and styles (a stencil node, an image node, a theme override,
 * a font family) are found by [ResourceReferenceSites], the one authority on those shapes; the
 * typed ones a resource carries at its top level are read here: a template's theme and an
 * attribute's code list. A child content (a template's lifted model) holds its own references, so
 * each content is read alone.
 */
object ContentReferences {
    fun of(kind: String, payload: JsonNode): Set<ContentReference> = buildSet {
        ResourceReferenceSites.scan(payload).forEach { site ->
            val key = site.key
            if (key.isNotBlank()) add(ContentReference(site.catalogKey?.takeIf { it.isNotBlank() }, site.kind.type.wireName, key))
        }
        when (kind) {
            "template" -> payload.text("themeKey")?.let { add(ContentReference(payload.text("themeCatalogKey"), "theme", it)) }

            "attribute" -> payload.get("codeListBinding")?.takeIf { it.isObject }?.let { binding ->
                (binding.text("key") ?: binding.text("slug"))?.let { add(ContentReference(binding.text("catalogKey"), "codeList", it)) }
            }
        }
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isString }?.asString()?.takeIf { it.isNotBlank() }
}
