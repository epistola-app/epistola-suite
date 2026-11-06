// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Pins the catalogs an archive from before wire v8 declares, as the tenant receiving it has them
 * (#1319). Such an archive pinned nothing: migrating it declares every catalog it names under that
 * catalog's key, in its own registry and namespace, pinning no release (contract ADR 0003). Received,
 * it is a release, and a release pins every catalog it declares; else it cannot travel as a wire-v8
 * archive, nor be copied into a draft.
 *
 * A v7 catalog's content named catalogs of its own tenant by key, so each is resolved as the 2.0
 * upgrade resolves a 1.x draft's (`V20261101001000__core_2_0_dependencies`): to the tenant's one
 * catalog with that key, whatever namespace it is in (the system catalog's is Epistola's), and pinned
 * to its highest release, as releasing would pin it. A key no catalog of the tenant has, or two
 * share, or one with no release, keeps the declaration as migrated.
 */
@Component
class LegacyDependencyPins(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
) {
    /** [declared], each one pinned to the release of the tenant's catalog it names where there is one. */
    fun pin(tenantKey: TenantKey, declared: List<CatalogDependency>): List<CatalogDependency> {
        if (declared.isEmpty()) return declared
        return jdbi.withHandle<List<CatalogDependency>, Exception> { handle ->
            val byKey = handle.createQuery("SELECT catalog_key, registry, namespace FROM catalogs WHERE tenant_key = :t AND catalog_key IN (<keys>)")
                .bind("t", tenantKey)
                .bindList("keys", declared.map { it.catalogKey }.distinct())
                .map { rs, _ -> CatalogDependencyDeclaration(rs.getString("registry"), rs.getString("namespace"), rs.getString("catalog_key")) }
                .list()
                .groupBy { it.catalogKey }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
            declared.map { dependency ->
                if (dependency.releaseVersion != null) return@map dependency
                val catalog = byKey[dependency.catalogKey] ?: return@map dependency
                val release = store.highest(handle, tenantKey, catalog) ?: return@map dependency
                dependency.copy(
                    registry = catalog.registry,
                    namespace = catalog.namespace,
                    releaseVersion = release.version,
                    releaseFingerprint = release.fingerprint,
                )
            }
        }
    }
}
