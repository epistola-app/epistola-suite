// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogAddress
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.CatalogNamesApplier
import app.epistola.suite.catalog.ContentSource
import app.epistola.suite.catalog.queries.ListCatalogEditability
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
 * moved into another catalog, and catalogs upgraded from 1.x, which had no declarations. A
 * reference to a catalog the tenant does not have is left alone; releasing refuses it.
 */
@Component
class MissingDependencyDeclarer(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val store: CatalogDependencyStore,
    private val catalogNames: CatalogNamesApplier,
) {
    /** @return the declarations added. */
    fun declareMissing(tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDependencyDeclaration> {
        val content = contentBuilder.build(tenantKey, catalogKey)
        return jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
            val added = missing(handle, tenantKey, catalogKey, content)
            if (added.isNotEmpty()) store.replace(handle, tenantKey, catalogKey, store.declarations(handle, tenantKey, catalogKey) + added)
            added
        }
    }

    private fun missing(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, content: CatalogContent): List<CatalogDependencyDeclaration> {
        val declared = store.declarations(handle, tenantKey, catalogKey)
        // A reference names its catalog by alias, which is the catalog's key here (DependencyAliases):
        // an alias no declaration has names a catalog of the tenant by that key, if it has one.
        val names = names(handle, tenantKey)
        // The manifest's index names the resources the content uses in other catalogs; a variant's
        // attributes name other catalogs by their qualifier, which the index does not list.
        val attributeAliases = content.resourceDetails.values
            .mapNotNull { it.resource as? app.epistola.catalog.protocol.TemplateResource }
            .flatMap { template -> template.variants.flatMap { it.attributes.orEmpty().keys } }
            .let(::attributeCatalogs)
            .map { it.value }
        val referenced = (content.dependencies.orEmpty().map { it.catalogKey } + attributeAliases)
            .distinct()
            .filter { alias -> alias != catalogKey.value && declared.none { it.alias == alias } }
            .mapNotNull(names::get)
        return referenced.map { (registry, namespace, key) ->
            val declaration = CatalogDependencyDeclaration(registry, namespace, key)
            store.highest(handle, tenantKey, declaration)
                ?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                ?: store.nextVersionOf(handle, tenantKey, declaration)?.let { (key, label) -> declaration.copy(draftId = key, draftName = label) }
                ?: declaration
        }
    }

    /**
     * The catalogs [catalogKey]'s content from [source] uses that the tenant does not have, by key: a
     * reference whose catalog this tenant has no catalog of that key for, which no declaration can name.
     */
    fun unknownCatalogs(tenantKey: TenantKey, catalogKey: CatalogKey, source: ContentSource): List<String> {
        val known = jdbi.withHandle<Set<String>, Exception> { handle ->
            handle.createQuery("SELECT catalog_key FROM catalogs WHERE tenant_key = :t").bind("t", tenantKey).mapTo(String::class.java).set()
        }
        return contentBuilder.build(tenantKey, catalogKey, source).dependencies.orEmpty()
            .map { it.catalogKey }
            .filter { it !in known }
            .distinct()
            .sorted()
    }

    /**
     * Every catalog of the tenant by key, with its registry and namespace. Two catalogs can share a key
     * (ADR 0034, phase 4): such a key means the one the running work names ([CatalogNames]), and,
     * named nowhere, none, since a reference by it could mean either.
     */
    private fun names(handle: Handle, tenantKey: TenantKey): Map<String, Triple<String, String, String>> = handle
        .createQuery("SELECT catalog_key, registry, namespace FROM catalogs WHERE tenant_key = :t")
        .bind("t", tenantKey)
        .map { rs, _ -> Triple(rs.getString("registry"), rs.getString("namespace"), rs.getString("catalog_key")) }
        .list()
        .groupBy { it.third }
        .mapNotNull { (key, catalogs) ->
            val named = CatalogNames.current()[key]
            (if (named != null) catalogs.firstOrNull { it.second == named } else catalogs.singleOrNull())?.let { key to it }
        }
        .toMap()

    /**
     * Pins every declaration of [catalogKey] that pins nothing (and names no draft) to the highest
     * release of its catalog, where there is one now: the 2.0 upgrade declares a catalog before the
     * catalog it depends on is released. A declaration whose catalog still has no release stays
     * unpinned, which releasing refuses. @return the declarations pinned.
     */
    fun pinUnpinned(tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDependencyDeclaration> = jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val declarations = store.declarations(handle, tenantKey, catalogKey)
        val pinned = declarations.map { declaration ->
            if (declaration.pinned || declaration.namesDraft) return@map declaration
            store.highest(handle, tenantKey, declaration)?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) } ?: declaration
        }
        if (pinned != declarations) store.replace(handle, tenantKey, catalogKey, pinned)
        pinned.filter { it !in declarations }
    }

    /** [declareMissing] for every catalog of [tenantKey] that can be changed here (ADR 0032), each in its namespace. */
    fun declareMissingInTenant(tenantKey: TenantKey): Map<CatalogAddress, List<CatalogDependencyDeclaration>> = ListCatalogEditability(tenantKey).query().editableCatalogs
        .sortedWith(compareBy({ it.key.value }, { it.namespace }))
        .associateWith { catalog -> catalogNames.named(catalog.key, catalog.namespace) { declareMissing(tenantKey, catalog.key) } }
        .filterValues { it.isNotEmpty() }
}
