// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.CatalogDependency

/**
 * The aliases content names other catalogs by (contract ADR 0003, amendment of 2026-10-22; ADR 0034).
 *
 * On the wire a reference into another catalog names an alias, and the catalog's dependency list
 * says which catalog (registry, namespace, key) the alias stands for. This installation tells a
 * tenant's catalogs apart by key until ADR 0034's phase 4 (`uq_catalogs_key_until_named`), stores
 * every reference by the key of the catalog it names, and declares every dependency under its key,
 * the contract's default alias. So for everything it holds an alias resolves through the declaration
 * to the catalog of that key, and the content it stores and the content it sends are the same.
 *
 * An archive whose dependency list names a catalog by another alias would need its references
 * rewritten to be stored, which would change its content and so its content hash (V6): it would no
 * longer be the release it says it is. It is refused instead, until catalogs are told apart by
 * namespace and key.
 */
object DependencyAliases {
    /** The finding code of an alias this installation cannot store ([unsupported]). */
    const val ALIAS_UNSUPPORTED = "CATALOG_DEPENDENCY_ALIAS_UNSUPPORTED"

    /**
     * A message per declaration of [declared] whose alias is not its catalog's key, keyed by its path
     * in `catalog.json`: what an import refuses.
     */
    fun unsupported(declared: List<CatalogDependency>): Map<String, String> = declared.withIndex()
        .filter { (_, dependency) -> dependency.alias != dependency.catalogKey }
        .associate { (index, dependency) ->
            "catalog.json.catalogDependencies[$index].alias" to
                "The catalog names ${dependency.registry}/${dependency.namespace}/${dependency.catalogKey} as '${dependency.alias}'. " +
                "This installation names every catalog a catalog uses by that catalog's key, until catalogs are told apart by " +
                "namespace and key; export it with each dependency under its key."
        }

    /**
     * The catalog key [alias] stands for in [declared]: the declaration's, or [alias] itself for a
     * catalog nothing declares (the catalog itself, or one left undeclared).
     */
    fun catalogKeyOf(alias: String, declared: List<CatalogDependencyDeclaration>): String = declared.firstOrNull { it.alias == alias }?.catalogKey ?: alias
}
