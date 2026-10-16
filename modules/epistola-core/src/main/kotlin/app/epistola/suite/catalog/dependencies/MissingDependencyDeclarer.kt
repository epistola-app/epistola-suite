// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Declares, for an authored catalog, every catalog its content references but does not declare:
 * with selector `*`, locked to the highest release there is, or unlocked when there is none.
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
        val own = Triple(content.catalog.registry, content.catalog.namespace, content.catalog.key)
        return jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
            val declared = store.declarations(handle, tenantKey, catalogKey)
            val referenced = content.dependencies.orEmpty()
                .mapNotNull { ref -> ref.registry?.let { registry -> ref.namespace?.let { Triple(registry, it, ref.catalogKey) } } }
                .distinct()
                .filter { it != own && declared.none { declaration -> declaration.identity == it } }
            val added = referenced.map { (registry, namespace, key) ->
                val declaration = CatalogDependencyDeclaration(registry, namespace, key, "*")
                store.highestMatch(handle, tenantKey, declaration)
                    ?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                    ?: declaration
            }
            if (added.isNotEmpty()) store.replace(handle, tenantKey, catalogKey, declared + added)
            added
        }
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
