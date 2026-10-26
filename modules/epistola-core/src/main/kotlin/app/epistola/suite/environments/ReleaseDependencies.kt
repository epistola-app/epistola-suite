// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/** Why a release a deployed release renders with cannot be served (contract `MissingReleaseDependencyReason`). */
enum class MissingDependencyReason {
    /** The tenant has the release with its content; the environment does not serve it. */
    NOT_DEPLOYED,

    /** The tenant has no such release: no catalog of that name, or not that release of it. */
    NOT_INSTALLED,

    /**
     * Deploying dependencies along was asked for, but the tenant does not have the release. This
     * installation does not fetch a release from its registry, so a release it does not have stays
     * unavailable.
     */
    UNAVAILABLE,
}

/**
 * A release of another catalog that a release renders with, which the environment does not serve.
 * Named as the pin names it: [catalogKey] is the catalog's key within [registry] and [namespace],
 * which is also its key in the tenant when the tenant has it.
 */
data class MissingReleaseDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String,
    val reason: MissingDependencyReason,
) {
    /** The tenant's catalog, for a dependency the tenant has (every reason but [MissingDependencyReason.NOT_INSTALLED]). */
    fun tenantCatalog(tenantKey: TenantKey): CatalogId = CatalogId(namespace, CatalogKey.of(catalogKey), TenantId(tenantKey))

    override fun toString(): String = "$registry/$namespace/$catalogKey@$releaseVersion"
}

/**
 * A release cannot be deployed because the environment does not serve the releases of other
 * catalogs it renders with, and they were not (or could not be) deployed along. Nothing was deployed.
 *
 * @property missing every missing release, direct and transitive, with why.
 * @property includedDependencies whether deploying them along was asked for.
 */
class ReleaseDependenciesMissingException(
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
    val version: String,
    val missing: List<MissingReleaseDependency>,
    val includedDependencies: Boolean,
) : RuntimeException(
    if (includedDependencies) {
        "${catalogKey.value} $version cannot be deployed to ${environmentKey.value} with its dependencies: " +
            missing.joinToString { "$it (${it.reason})" }
    } else {
        "${catalogKey.value} $version renders with releases ${environmentKey.value} does not serve: " +
            missing.joinToString { "$it (${it.reason})" }
    },
) {
    /**
     * Whether deploying again with its dependencies would succeed: every missing release is one the
     * tenant has and only the environment lacks. A release the tenant does not have, or has without
     * content, cannot be deployed along, so offering to would only be refused again.
     */
    val deployableAlong: Boolean get() = !includedDependencies && missing.all { it.reason == MissingDependencyReason.NOT_DEPLOYED }
}

/**
 * The releases of other catalogs a release renders with (contract ADR 0003): the pins it was cut
 * with in `catalog_version_dependencies` (ADR 0036), and, through each pinned release the tenant has, that
 * release's own pins, nearest first. An environment that serves the release must serve every one of
 * them exactly, by version and, when the pin recorded one, by fingerprint.
 *
 * A pin with no release version, as a release imported from catalog wire v7 recorded, names no
 * release and cannot be checked: it is skipped, and rendering resolves that catalog as it would for
 * a release cut before 2.0. A pin on the release's own catalog is skipped too, as rendering skips
 * it: a release renders its own catalog from itself.
 */
@Component
class ReleaseDependencies(
    private val dependencyStore: CatalogDependencyStore,
) {

    /**
     * The releases [version] of [catalog] renders with that [environmentKey] does not serve, direct
     * and transitive, each with why, in the order they were found.
     */
    fun missing(handle: Handle, environmentKey: EnvironmentKey, catalog: CatalogId, version: String): List<MissingReleaseDependency> {
        val tenantKey = catalog.tenantKey
        val found = mutableListOf<MissingReleaseDependency>()
        val visited = mutableSetOf<Pair<String, String>>()
        val queue = ArrayDeque(listOf(catalog to version))
        while (queue.isNotEmpty()) {
            val (fromCatalog, fromVersion) = queue.removeFirst()
            for (pin in dependencyStore.releasePins(handle, fromCatalog, fromVersion)) {
                val pinnedVersion = pin.releaseVersion ?: continue
                val pinned = dependencyStore.catalogNamed(handle, tenantKey, pin)
                if (pinned == catalog) continue
                if (!visited.add("${pin.registry}/${pin.namespace}/${pin.catalogKey}" to pinnedVersion)) continue

                // The pinned release, by its content (ADR 0034): a pin taken as V5 holds for the same release received as V6.
                val release = pinned?.let { release(handle, it, pinnedVersion) }
                    ?.takeIf { pin.releaseFingerprint == null || dependencyStore.pinHolds(handle, tenantKey, pin) }
                val reason = when {
                    pinned == null || release == null -> MissingDependencyReason.NOT_INSTALLED
                    serves(handle, environmentKey, pinned, pinnedVersion) -> null
                    else -> MissingDependencyReason.NOT_DEPLOYED
                }
                if (reason != null) {
                    found += MissingReleaseDependency(pin.registry, pin.namespace, pin.catalogKey, pinnedVersion, reason)
                }
                // A release the tenant has renders with its own pins, whether or not it is served yet.
                if (pinned != null && release != null) queue.addLast(pinned to pinnedVersion)
            }
        }
        return found
    }

    /** Every release [environmentKey] serves, as catalog and version. */
    fun served(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey): List<Pair<CatalogId, String>> = handle
        .createQuery(
            """
            SELECT cat.namespace, cat.catalog_key, d.version FROM environment_catalog_deployments d
            JOIN catalogs cat ON cat.tenant_key = d.tenant_key AND cat.catalog_id = d.catalog_id
            WHERE d.tenant_key = :t AND d.environment_key = :e AND d.version IS NOT NULL
            """,
        )
        .bind("t", tenantKey)
        .bind("e", environmentKey)
        .map { rs, _ -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), TenantId(tenantKey)) to rs.getString("version") }
        .list()

    /**
     * The releases of other catalogs that [releases] render with, direct and transitive, as catalog
     * and version: what an environment serving [releases] has to keep serving.
     */
    fun needed(handle: Handle, releases: List<Pair<CatalogId, String>>): Set<Pair<CatalogId, String>> {
        val needed = mutableSetOf<Pair<CatalogId, String>>()
        val queue = ArrayDeque(releases)
        while (queue.isNotEmpty()) {
            val (fromCatalog, fromVersion) = queue.removeFirst()
            for (pin in dependencyStore.releasePins(handle, fromCatalog, fromVersion)) {
                val pinnedVersion = pin.releaseVersion ?: continue
                val pinned = dependencyStore.catalogNamed(handle, fromCatalog.tenantKey, pin) ?: continue
                if (pinned == fromCatalog) continue
                if (needed.add(pinned to pinnedVersion)) queue.addLast(pinned to pinnedVersion)
            }
        }
        return needed
    }

    private data class Release(val fingerprint: String)

    private fun release(handle: Handle, catalog: CatalogId, version: String): Release? = handle
        .createQuery("SELECT fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("v", version)
        .map { rs, _ -> Release(rs.getString("fingerprint")) }
        .findOne()
        .orElse(null)

    private fun serves(handle: Handle, environmentKey: EnvironmentKey, catalog: CatalogId, version: String): Boolean = handle
        .createQuery(
            """
            SELECT EXISTS (SELECT 1 FROM environment_catalog_deployments
                           WHERE tenant_key = :t AND environment_key = :e AND catalog_id = ${catalogAt("t", "c")} AND version = :v)
            """,
        )
        .bind("t", catalog.tenantKey)
        .bind("e", environmentKey)
        .bindCatalog("c", catalog)
        .bind("v", version)
        .mapTo(Boolean::class.java)
        .one()
}
