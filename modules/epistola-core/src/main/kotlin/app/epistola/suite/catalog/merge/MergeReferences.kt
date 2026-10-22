// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.graph.ResourceReferenceSites
import app.epistola.suite.common.ids.CatalogKey
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * One reference an entry of a merge holds: to a resource of [type] (as the catalog format names it:
 * `theme`, `stencil`, `image`, `font`, `codeList`, `attribute`) and [key], in [catalogKey], or in the
 * catalog itself when that is null.
 */
data class EntryReference(val type: String, val catalogKey: String?, val key: String) {
    /** Whether it points into another catalog than [own]. */
    fun intoOther(own: CatalogKey): Boolean = catalogKey != null && catalogKey != own.value

    /**
     * The merge entry it needs (`type/key`): the resource itself within [own], else the dependency on
     * the catalog it points into, through which it resolves.
     */
    fun entryIn(own: CatalogKey): String = if (intoOther(own)) CatalogSetting.dependencyKey(catalogKey!!) else "$type/$key"

    /** How a sentence names it: `theme 'brand'`, or `theme 'brand' of 'shared'` in another catalog. */
    fun label(own: CatalogKey): String = "${MergeReferences.word(type)} '$key'" + if (intoOther(own)) " of '$catalogKey'" else ""
}

/**
 * The references an entry of a merge holds, as the catalog format carries them: a template's theme,
 * stencils, images, fonts and theme overrides in its documents and the attributes its variants name;
 * a stencil's content; a theme's fonts; an attribute's code list; the catalog's default theme.
 * Embedded references are found by [ResourceReferenceSites], the one authority on them. An image named
 * without its catalog is looked up tenant-wide, so it names no entry.
 */
@Component
class MergeReferences(private val objectMapper: ObjectMapper) {

    /** The references [value] holds. */
    fun of(value: MergeValue): List<EntryReference> = when (value) {
        is MergeValue.Resource -> of(value.resource)

        is MergeValue.Setting -> (value.setting as? CatalogSetting.DefaultTheme)?.theme
            ?.let { listOf(EntryReference("theme", it.catalogKey.value, it.themeKey.value)) }
            .orEmpty()
    }

    /** The references [resource] holds. */
    fun of(resource: CatalogResource): List<EntryReference> = when (resource) {
        is TemplateResource -> buildList {
            resource.themeKey?.let { add(EntryReference("theme", resource.themeCatalogKey, it)) }
            addAll(embedded(resource.templateModel))
            resource.variants.forEach { variant ->
                variant.templateModel?.let { addAll(embedded(it)) }
                variant.attributes.orEmpty().keys.forEach { name ->
                    // A variant names another catalog's attribute as `<catalogKey>.<key>`.
                    add(if ('.' in name) EntryReference("attribute", name.substringBefore('.'), name.substringAfter('.')) else EntryReference("attribute", null, name))
                }
            }
        }

        is StencilResource -> embedded(resource.content)

        is ThemeResource -> embedded(resource.documentStyles) + embedded(resource.blockStylePresets)

        is AttributeResource -> listOfNotNull(resource.codeListBinding?.let { EntryReference("codeList", it.catalogKey, it.key) })

        else -> emptyList()
    }.distinct()

    private fun embedded(payload: Any?): List<EntryReference> {
        if (payload == null) return emptyList()
        val tree = objectMapper.valueToTree<JsonNode>(payload)
        return ResourceReferenceSites.scan(tree).mapNotNull { site ->
            if (!site.kind.relativeWhenUnqualified && site.catalogKey == null) return@mapNotNull null
            EntryReference(site.kind.type.wireName, site.catalogKey, site.key)
        }
    }

    companion object {
        /** How a sentence names a resource type. */
        fun word(type: String): String = when (type) {
            "codeList" -> "code list"
            else -> type
        }
    }
}
