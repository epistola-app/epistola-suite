// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogId
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

    /** The stencil's catalog, bound with [app.epistola.suite.catalog.bindCatalog]. */
    const val CATALOG_PARAM = "stencilRefCatalogKey"

    /** The tenant the statement reads, bound under this name by every caller. */
    private const val TENANT_PARAM = "tenantId"

    /**
     * True when [node] is an instance of [stencil]. [templateCatalog] is the catalog of the template
     * the node sits in — not the stencil's, which [stencil] carries — and answers for a node that
     * names no `catalogKey` of its own; [resolve] gives the catalog an alias names from it (ADR 0034,
     * section 9).
     */
    fun refersTo(node: Node, stencil: StencilId, templateCatalog: CatalogId, resolve: (String) -> CatalogId?): Boolean {
        if (node.type != StencilNodeKeys.NODE_TYPE) return false
        val props = node.props ?: return false
        if (props[StencilNodeKeys.PROP_STENCIL_KEY] as? String != stencil.key.value) return false
        val alias = (props[StencilNodeKeys.PROP_CATALOG_KEY] as? String)?.takeIf { it.isNotBlank() }
        val refCatalog = if (alias == null || alias == templateCatalog.key.value) templateCatalog else resolve(alias)
        return refCatalog == stencil.catalogId
    }

    /**
     * SQL condition matching a stencil node row ([nodeAlias]`.value` from `jsonb_each` over the
     * template model's nodes) of a template aliased [templateAlias] against the stencil bound under
     * [KEY_PARAM] and [CATALOG_PARAM].
     */
    fun refersTo(nodeAlias: String, templateAlias: String): String = """(
        $nodeAlias.value ->> 'type' = '${StencilNodeKeys.NODE_TYPE}'
        AND $nodeAlias.value -> 'props' ->> '${StencilNodeKeys.PROP_STENCIL_KEY}' = :$KEY_PARAM
        AND ${referencedCatalog(nodeAlias, templateAlias)} = ${catalogAt(TENANT_PARAM, CATALOG_PARAM)}
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
        AND $nodeAlias.value -> 'props' ->> '${StencilNodeKeys.PROP_STENCIL_KEY}' = :$KEY_PARAM
        AND (
            ${referencedCatalog(nodeAlias, templateAlias)} = ${catalogAt(TENANT_PARAM, CATALOG_PARAM)}
            OR NOT EXISTS (
                SELECT 1 FROM release_wire_resources referenced
                JOIN catalog_current_versions referenced_version
                  ON referenced_version.tenant_key = referenced.tenant_key AND referenced_version.version_id = referenced.version_id
                WHERE referenced.tenant_key = $templateAlias.tenant_key
                  AND referenced_version.catalog_id = ${referencedCatalog(nodeAlias, templateAlias)}
                  AND referenced.resource_type = 'stencil'
                  AND referenced.resource_key = :$KEY_PARAM
            )
        )
    )"""

    /**
     * The `catalog_id` a node's stencil is in: the template's own catalog without an alias (or with
     * its own key), else the catalog the template's version declares under that alias (ADR 0034,
     * section 9); NULL for an alias it does not declare.
     */
    private fun referencedCatalog(nodeAlias: String, templateAlias: String): String {
        val alias = "NULLIF($nodeAlias.value -> 'props' ->> '${StencilNodeKeys.PROP_CATALOG_KEY}', '')"
        return """
            (CASE WHEN $alias IS NULL OR $alias = ${templateCatalog(templateAlias)} THEN $templateAlias.catalog_id
             ELSE (SELECT epistola_catalog_id(ref.tenant_key, ref.namespace, ref.dependency_key) FROM catalog_version_dependencies ref
                   WHERE ref.tenant_key = $templateAlias.tenant_key AND ref.version_id = $templateAlias.version_id AND ref.alias = $alias) END)
        """
    }

    /** The key of the catalog the template aliased [templateAlias] lives in. */
    private fun templateCatalog(templateAlias: String) = "(SELECT template_catalog.catalog_key FROM catalogs template_catalog" +
        " WHERE template_catalog.tenant_key = $templateAlias.tenant_key AND template_catalog.catalog_id = $templateAlias.catalog_id)"
}
