// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Declares a catalog the moment a write starts using it, so a draft's declarations always say
 * which other catalogs it uses (contract ADR 0003) and every surface declares the same way.
 *
 * A write that references a resource of another catalog the current version (the draft the
 * running message targets) does not declare yet declares that catalog: an authored one by naming
 * its next version, which is what the draft then renders it from ([DraftDependencyView]); a
 * subscribed one by pinning its installed release. Releasing pins a named draft once that draft is
 * released. A declaration already there is left as the author set it, and a reference to a catalog
 * the tenant does not have is left alone: releasing refuses it.
 */
@Component
class UsedCatalogDeclarer(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) {
    /** [declareCatalogs] for the catalogs [references] point into. @return the declarations added. */
    fun declare(tenantKey: TenantKey, catalogKey: CatalogKey, references: Collection<DependencyRef>): List<CatalogDependencyDeclaration> = declareCatalogs(tenantKey, catalogKey, references.map { CatalogKey.of(it.catalogKey) })

    /** [declareCatalogs] for one catalog a direct reference names, such as a template's theme or an attribute's code list. */
    fun declare(tenantKey: TenantKey, catalogKey: CatalogKey, used: CatalogKey?): List<CatalogDependencyDeclaration> = declareCatalogs(tenantKey, catalogKey, listOfNotNull(used))

    /** Declares each of [catalogs] that [catalogKey] does not declare yet, itself excepted. @return the declarations added. */
    fun declareCatalogs(tenantKey: TenantKey, catalogKey: CatalogKey, catalogs: Collection<CatalogKey>): List<CatalogDependencyDeclaration> {
        val used = catalogs.filter { it != catalogKey }.map { it.value }.toSet()
        if (used.isEmpty()) return emptyList()
        return jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
            val declared = store.declarations(handle, tenantKey, catalogKey)
            val missing = used.filter { key -> declared.none { it.catalogKey == key } }
            if (missing.isEmpty()) return@inTransaction emptyList()
            val added = handle.createQuery(
                "SELECT catalog_key, registry, namespace, type FROM catalogs WHERE tenant_key = :t AND catalog_key IN (<keys>) ORDER BY catalog_key",
            )
                .bind("t", tenantKey)
                .bindList("keys", missing)
                .map { rs, _ -> Pair(CatalogDependencyDeclaration(rs.getString("registry"), rs.getString("namespace"), rs.getString("catalog_key")), rs.getString("type")) }
                .list()
                .map { (declaration, type) ->
                    if (type == CatalogType.AUTHORED.name) {
                        store.nextVersionOf(handle, tenantKey, declaration)?.let { (key, label) -> declaration.copy(draftId = key, draftName = label) }
                    } else {
                        store.highest(handle, tenantKey, declaration)?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
                    } ?: declaration
                }
            if (added.isNotEmpty()) store.replace(handle, tenantKey, catalogKey, declared + added)
            added
        }
    }
}
