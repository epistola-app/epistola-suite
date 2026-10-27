// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Declares, for an authored catalog, every catalog its content references but does not declare:
 * pinned to the highest release there is, or, when there is none, naming that catalog's next
 * version (contract ADR 0002: a draft may build on another catalog's draft; releasing that catalog
 * turns the declaration into a pin of its release). A catalog with neither stays unpinned.
 *
 * For where references into another catalog arise without an author declaring them: a resource
 * moved into another catalog. A
 * reference to a catalog the tenant does not have is left alone; releasing refuses it.
 */
@Component
class MissingDependencyDeclarer(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val store: CatalogDependencyStore,
) {
    /** @return the declarations added. */
    fun declareMissing(catalog: CatalogId): List<CatalogDependencyDeclaration> {
        val content = contentBuilder.build(catalog)
        return jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
            val added = missing(handle, catalog, content)
            if (added.isNotEmpty()) store.replace(handle, catalog, store.declarations(handle, catalog) + added)
            added
        }
    }

    private fun missing(handle: Handle, catalog: CatalogId, content: CatalogContent): List<CatalogDependencyDeclaration> {
        val declared = store.declarations(handle, catalog)
        // A reference names its catalog by alias, which is the catalog's key here (DependencyAliases):
        // an alias no declaration has names a catalog of the tenant by that key, if it has one.
        val names = names(handle, catalog.tenantKey)
        // The manifest's index names the resources the content uses in other catalogs; a variant's
        // attributes name other catalogs by their qualifier, which the index does not list.
        val attributeAliases = content.resourceDetails.values
            .mapNotNull { it.resource as? app.epistola.catalog.protocol.TemplateResource }
            .flatMap { template -> template.variants.flatMap { it.attributes.orEmpty().keys } }
            .let(::attributeCatalogs)
            .map { it.value }
        val referenced = (content.dependencies.orEmpty().map { it.catalogKey } + attributeAliases)
            .distinct()
            .filter { alias -> alias != catalog.key.value && declared.none { it.alias == alias } }
            .mapNotNull(names::get)
        return referenced.map { (registry, namespace, key) ->
            val declaration = CatalogDependencyDeclaration(registry, namespace, key)
            store.highest(handle, catalog.tenantKey, declaration)
                ?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                ?: store.nextVersionOf(handle, catalog.tenantKey, declaration)?.let { (key, label) -> declaration.copy(draftId = key, draftName = label) }
                ?: declaration
        }
    }

    /**
     * Every catalog of the tenant by key, with its registry and namespace, for an alias no declaration
     * has. A key two catalogs share means neither (ADR 0034): a reference by it could mean either.
     */
    private fun names(handle: Handle, tenantKey: TenantKey): Map<String, Triple<String, String, String>> = handle
        .createQuery("SELECT catalog_key, registry, namespace FROM catalogs WHERE tenant_key = :t")
        .bind("t", tenantKey)
        .map { rs, _ -> Triple(rs.getString("registry"), rs.getString("namespace"), rs.getString("catalog_key")) }
        .list()
        .groupBy { it.third }
        .mapNotNull { (key, catalogs) -> catalogs.singleOrNull()?.let { key to it } }
        .toMap()

    /** [declareMissing] for every catalog of [tenantKey] that can be changed here (ADR 0032), each in its namespace. */
    fun declareMissingInTenant(tenantKey: TenantKey): Map<CatalogId, List<CatalogDependencyDeclaration>> = ListCatalogEditability(tenantKey).query().editableCatalogs
        .sortedWith(compareBy({ it.key.value }, { it.namespace }))
        .associateWith { catalog -> declareMissing(catalog) }
        .filterValues { it.isNotEmpty() }
}
