// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * The releases of [namespace]/[catalogKey] a registry holds (alpha), highest number first, each
 * with what its manifest says about it. Tags that are not a release number, and manifests that are
 * not a catalog release, are left out. At most [MAX_LISTED] are read, one manifest request each.
 *
 * What a manifest says is a label: [RegistryRelease.fingerprint] is shown, never trusted; installing
 * recomputes it from the archive.
 */
data class ListRegistryReleases(
    override val tenantKey: TenantKey,
    val registry: String,
    val namespace: String,
    val catalogKey: String,
) : Query<RegistryReleases>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW

    companion object {
        const val MAX_LISTED = 50
    }
}

/**
 * @property conflict why installing would be refused before anything is downloaded (the catalog
 *   key is held by another catalog here), or null.
 */
data class RegistryReleases(
    val registry: String,
    val repository: String,
    val namespace: String,
    val catalogKey: String,
    val releases: List<RegistryRelease>,
    val conflict: String?,
) {
    /** Whether there is a release here the tenant can install now. */
    val installable: Boolean get() = conflict == null && releases.any { !it.present }
}

/**
 * One release in the registry. [present] when this tenant has that release of this catalog already.
 */
data class RegistryRelease(
    val version: String,
    val fingerprint: String?,
    val digest: String,
    val sizeBytes: Long,
    val present: Boolean,
)

@Component
class ListRegistryReleasesHandler(
    private val registries: CatalogRegistries,
) : QueryHandler<ListRegistryReleases, RegistryReleases> {
    override fun handle(query: ListRegistryReleases): RegistryReleases {
        val registry = registries.named(query.registry)
        val repository = registries.repository(registry, query.namespace, query.catalogKey)
        val namespace = query.namespace.trim()
        val catalogKey = CatalogKey.of(query.catalogKey.trim())
        val client = registries.client(registry)

        val local = GetCatalog(query.tenantKey, catalogKey).query()
        val conflict = RegistryInstallRules.conflict(local, RegistrySourceUri(registry.name, namespace, catalogKey.value))
        val here = if (local != null && conflict == null) ListCatalogReleases(query.tenantKey, catalogKey).query().associate { it.version to it.fingerprint } else emptyMap()

        val releases = releaseVersionsNewestFirst(client.tags(repository)).take(ListRegistryReleases.MAX_LISTED).mapNotNull { version ->
            val pulled = client.manifest(repository, version) ?: return@mapNotNull null
            val archive = pulled.manifest.archive?.takeIf { pulled.manifest.isCatalogRelease } ?: return@mapNotNull null
            RegistryRelease(
                version = version,
                fingerprint = pulled.manifest.fingerprint,
                digest = pulled.digest,
                sizeBytes = archive.size,
                present = version in here,
            )
        }
        return RegistryReleases(registry.name, repository, namespace, catalogKey.value, releases, conflict)
    }
}
