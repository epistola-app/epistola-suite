// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.ContentSource
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
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
        val own = Triple(content.catalog.registry, content.catalog.namespace, content.catalog.key)
        val declared = store.declarations(handle, tenantKey, catalogKey)
        val referenced = content.dependencies.orEmpty()
            .mapNotNull { ref -> ref.registry?.let { registry -> ref.namespace?.let { Triple(registry, it, ref.catalogKey) } } }
            .distinct()
            .filter { it != own && declared.none { declaration -> declaration.identity == it } }
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
     * reference whose catalog has no registry or namespace here, which no declaration can name.
     */
    fun unknownCatalogs(tenantKey: TenantKey, catalogKey: CatalogKey, source: ContentSource): List<String> = contentBuilder.build(tenantKey, catalogKey, source).dependencies.orEmpty()
        .filter { it.registry == null || it.namespace == null }
        .map { it.catalogKey }
        .distinct()
        .sorted()

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

    /** [declareMissing] for every authored catalog of [tenantKey]. */
    fun declareMissingInTenant(tenantKey: TenantKey): Map<CatalogKey, List<CatalogDependencyDeclaration>> {
        val authored = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT catalog_key FROM catalogs WHERE tenant_key = :t AND type = :type ORDER BY catalog_key")
                .bind("t", tenantKey)
                .bind("type", CatalogType.AUTHORED.name)
                .mapTo(String::class.java)
                .list()
        }
        return authored.map(CatalogKey::of).associateWith { declareMissing(tenantKey, it) }.filterValues { it.isNotEmpty() }
    }
}
