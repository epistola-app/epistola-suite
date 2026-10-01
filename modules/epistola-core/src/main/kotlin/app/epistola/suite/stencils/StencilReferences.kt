// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.common.ids.StencilId
import app.epistola.template.model.Node

/**
 * Which stencil a stencil node refers to (#1024).
 *
 * Stencil keys are unique only within a catalog, so the key alone is ambiguous: a node refers to
 * `(catalog, key)` through its `stencilId` prop plus its `catalogKey` prop, and a node without a
 * `catalogKey` means the stencil in its **owning template's** catalog. This is the rule
 * `PublishVersion` and catalog import already apply; every upgrade and usage query uses it too.
 */
object StencilReferences {
    const val KEY_PARAM = "stencilRefKey"
    const val CATALOG_PARAM = "stencilRefCatalogKey"

    /** True when [node] is an instance of [stencil], in a template owned by [owningCatalogKey]. */
    fun refersTo(node: Node, stencil: StencilId, owningCatalogKey: String): Boolean {
        if (node.type != StencilNodeKeys.NODE_TYPE) return false
        val props = node.props ?: return false
        if (props[StencilNodeKeys.PROP_STENCIL_ID] as? String != stencil.key.value) return false
        val refCatalog = (props[StencilNodeKeys.PROP_CATALOG_KEY] as? String)?.takeIf { it.isNotBlank() } ?: owningCatalogKey
        return refCatalog == stencil.catalogKey.value
    }

    /**
     * SQL condition matching a stencil node row ([nodeAlias]`.value` from `jsonb_each` over the
     * template model's nodes) of a template aliased [templateAlias] against the stencil bound under
     * [KEY_PARAM] and [CATALOG_PARAM].
     */
    fun refersTo(nodeAlias: String, templateAlias: String): String = """(
        $nodeAlias.value ->> 'type' = '${StencilNodeKeys.NODE_TYPE}'
        AND $nodeAlias.value -> 'props' ->> '${StencilNodeKeys.PROP_STENCIL_ID}' = :$KEY_PARAM
        AND ${referencedCatalog(nodeAlias, templateAlias)} = :$CATALOG_PARAM
    )"""

    /**
     * What the **usage** queries count as using the stencil: [refersTo], plus a *dangling*
     * reference — one naming the same key in a catalog that no longer has a stencil with that key.
     *
     * After a resource move (relocation is a crude move: no aliases, the old address is free at
     * once) published templates still name the old address; reporting them under the stencil that
     * now carries the key is how an author finds them. A same-keyed stencil that still exists in
     * another catalog is not dangling, so it is never counted here (#1024). Upgrades stay on
     * [refersTo]: they rewrite only instances that name this exact stencil.
     */
    fun usedBy(nodeAlias: String, templateAlias: String): String = """(
        $nodeAlias.value ->> 'type' = '${StencilNodeKeys.NODE_TYPE}'
        AND $nodeAlias.value -> 'props' ->> '${StencilNodeKeys.PROP_STENCIL_ID}' = :$KEY_PARAM
        AND (
            ${referencedCatalog(nodeAlias, templateAlias)} = :$CATALOG_PARAM
            OR NOT EXISTS (
                SELECT 1 FROM stencils referenced
                WHERE referenced.tenant_key = $templateAlias.tenant_key
                  AND referenced.catalog_key = ${referencedCatalog(nodeAlias, templateAlias)}
                  AND referenced.id = :$KEY_PARAM
            )
        )
    )"""

    private fun referencedCatalog(nodeAlias: String, templateAlias: String) = "COALESCE(NULLIF($nodeAlias.value -> 'props' ->> '${StencilNodeKeys.PROP_CATALOG_KEY}', ''), $templateAlias.catalog_key)"
}
