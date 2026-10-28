// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.catalog.types.ResourceType
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
 * a font family) and an attribute's code list binding are found by [ResourceReferenceSites], the
 * one authority on those shapes; the ones a resource carries beside them its [type] names
 * ([ResourceType.references]: a template's theme and its variants' attributes). A child content (a
 * template's lifted model) holds its own references, so each content is read alone.
 */
object ContentReferences {
    fun of(payload: JsonNode, type: ResourceType<*>?): Set<ContentReference> = buildSet {
        ResourceReferenceSites.scan(payload).forEach { site ->
            val key = site.key
            if (key.isNotBlank()) add(ContentReference(site.catalogKey?.takeIf { it.isNotBlank() }, site.kind.type.wireName, key))
        }
        // Content retained before its type was stored in the envelope (ADR 0033) holds the resource bare.
        if (type != null) addAll(type.references(payload.get("resource")?.takeIf { it.isObject } ?: payload))
    }
}
