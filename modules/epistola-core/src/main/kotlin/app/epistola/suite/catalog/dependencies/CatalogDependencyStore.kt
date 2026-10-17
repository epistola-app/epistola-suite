// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.suite.catalog.CatalogVersionSelector
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * A catalog this catalog depends on (contract ADR 0003): named by registry, namespace and key,
 * selected by an npm range, and locked to the release [releaseVersion] with its [releaseFingerprint],
 * both null while no release matched.
 */
data class CatalogDependencyDeclaration(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val version: String,
    val releaseVersion: String? = null,
    val releaseFingerprint: String? = null,
) {
    val identity: Triple<String, String, String> get() = Triple(registry, namespace, catalogKey)

    val locked: Boolean get() = releaseVersion != null

    fun toWire(): CatalogDependency = CatalogDependency(registry, namespace, catalogKey, version, releaseVersion, releaseFingerprint)

    override fun toString(): String = "$registry~$namespace/$catalogKey@$version"

    companion object {
        fun of(wire: CatalogDependency): CatalogDependencyDeclaration = CatalogDependencyDeclaration(
            wire.registry,
            wire.namespace,
            wire.catalogKey,
            wire.version,
            wire.releaseVersion,
            wire.releaseFingerprint,
        )
    }
}

/** A release of a dependency, as a declaration locks or a release pins it. */
data class CatalogDependencyRelease(val version: String, val fingerprint: String)

/**
 * The SQL of declared dependencies, their locks and the pins of each release. Callers own the
 * transaction: every method takes the handle it runs on.
 */
@Component
class CatalogDependencyStore {

    fun declarations(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT registry, namespace, dependency_key, version_selector, release_version, release_fingerprint
            FROM catalog_dependencies
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}
            ORDER BY position
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ ->
            CatalogDependencyDeclaration(
                rs.getString("registry"),
                rs.getString("namespace"),
                rs.getString("dependency_key"),
                rs.getString("version_selector"),
                rs.getString("release_version"),
                rs.getString("release_fingerprint"),
            )
        }
        .list()

    /** Replaces the declarations of [catalogKey] with [declarations], in that order. */
    fun replace(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, declarations: List<CatalogDependencyDeclaration>) {
        handle.createUpdate("DELETE FROM catalog_dependencies WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .execute()
        if (declarations.isEmpty()) return
        val batch = handle.prepareBatch(
            """
            INSERT INTO catalog_dependencies (tenant_key, catalog_id, registry, namespace, dependency_key, version_selector, position,
                                              release_version, release_fingerprint)
            VALUES (:t, ${catalogAt("t", "c")}, :registry, :namespace, :key, :selector, :position, :releaseVersion, :releaseFingerprint)
            """,
        )
        declarations.forEachIndexed { position, declaration ->
            batch.bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("registry", declaration.registry)
                .bind("namespace", declaration.namespace)
                .bind("key", declaration.catalogKey)
                .bind("selector", declaration.version)
                .bind("position", position)
                .bind("releaseVersion", declaration.releaseVersion)
                .bind("releaseFingerprint", declaration.releaseFingerprint)
                .add()
        }
        batch.execute()
    }

    /** Records the declarations release [version] of [catalogKey] was cut with, each with its pin. */
    fun recordRelease(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, pins: List<CatalogDependencyDeclaration>) {
        if (pins.isEmpty()) return
        val batch = handle.prepareBatch(
            """
            INSERT INTO catalog_release_dependencies (tenant_key, catalog_id, version, registry, namespace, dependency_key,
                                                      version_selector, position, release_version, release_fingerprint)
            VALUES (:t, ${catalogAt("t", "c")}, :version, :registry, :namespace, :key, :selector, :position, :releaseVersion, :releaseFingerprint)
            """,
        )
        pins.forEachIndexed { position, pin ->
            batch.bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("registry", pin.registry)
                .bind("namespace", pin.namespace)
                .bind("key", pin.catalogKey)
                .bind("selector", pin.version)
                .bind("position", position)
                .bind("releaseVersion", pin.releaseVersion)
                .bind("releaseFingerprint", pin.releaseFingerprint)
                .add()
        }
        batch.execute()
    }

    /** The declarations release [version] of [catalogKey] was cut with, each with its pin. */
    fun releasePins(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT registry, namespace, dependency_key, version_selector, release_version, release_fingerprint
            FROM catalog_release_dependencies
            WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version
            ORDER BY position
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("version", version)
        .map { rs, _ ->
            CatalogDependencyDeclaration(
                rs.getString("registry"),
                rs.getString("namespace"),
                rs.getString("dependency_key"),
                rs.getString("version_selector"),
                rs.getString("release_version"),
                rs.getString("release_fingerprint"),
            )
        }
        .list()

    /**
     * The tenant's catalog that [declaration] names, by registry, namespace and key, or null when
     * the tenant has none: a catalog with the same key in another namespace is another catalog.
     */
    fun catalogNamed(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): CatalogKey? = handle
        .createQuery("SELECT catalog_key FROM catalogs WHERE tenant_key = :t AND registry = :registry AND namespace = :namespace AND catalog_key = :key")
        .bind("t", tenantKey)
        .bind("registry", declaration.registry)
        .bind("namespace", declaration.namespace)
        .bind("key", declaration.catalogKey)
        .mapTo(String::class.java)
        .findOne()
        .map(CatalogKey::of)
        .orElse(null)

    /**
     * The releases a declaration may lock to: those of the catalog it names that kept their content,
     * so they can be rendered, and whose fingerprint names its algorithm, which a pin copies. A
     * release cut before 2.0 has a bare fingerprint and is not a candidate.
     */
    fun candidates(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): List<CatalogDependencyRelease> {
        val catalog = catalogNamed(handle, tenantKey, declaration) ?: return emptyList()
        return handle
            .createQuery("SELECT version, fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND content_retained")
            .bind("t", tenantKey)
            .bind("c", catalog)
            .map { rs, _ -> CatalogDependencyRelease(rs.getString("version"), rs.getString("fingerprint")) }
            .list()
            .filter { QualifiedCatalogFingerprint.parse(it.fingerprint) != null }
    }

    /** The highest candidate release [declaration]'s selector matches, or null when none does. */
    fun highestMatch(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): CatalogDependencyRelease? {
        val selector = CatalogVersionSelector.parseOrNull(declaration.version) ?: return null
        val candidates = candidates(handle, tenantKey, declaration).associateBy { it.version }
        return selector.highestMatch(candidates.keys)?.let(candidates::getValue)
    }

    /**
     * Whether [declaration]'s lock still holds: its selector matches the locked release, and the
     * tenant still has that release, with its content and the fingerprint the lock recorded.
     */
    fun lockHolds(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): Boolean {
        val version = declaration.releaseVersion ?: return false
        if (CatalogVersionSelector.parseOrNull(declaration.version)?.matches(version) != true) return false
        return candidates(handle, tenantKey, declaration).any { it.version == version && it.fingerprint == declaration.releaseFingerprint }
    }
}
