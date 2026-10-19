// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.semver4j.Semver
import org.springframework.stereotype.Component

/**
 * A catalog this catalog depends on (contract ADR 0003): named by registry, namespace and key, and
 * pinned to the exact release [releaseVersion] with its [releaseFingerprint]. In a draft it may name
 * a draft of that catalog instead ([draftId], its public id, and [draftName]), to build on work not
 * released yet. All are null for a declaration that pins nothing yet (one migrated from wire v7).
 * Releasing refuses either until it pins a release.
 */
data class CatalogDependencyDeclaration(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String? = null,
    val releaseFingerprint: String? = null,
    val draftId: String? = null,
    val draftName: String? = null,
) {
    val identity: Triple<String, String, String> get() = Triple(registry, namespace, catalogKey)

    val pinned: Boolean get() = releaseVersion != null

    /** It names a draft of the dependency, which a release cannot pin. */
    val namesDraft: Boolean get() = draftId != null

    /** A release never pins a draft, so the wire form of a declaration naming one pins nothing. */
    fun toWire(): CatalogDependency = CatalogDependency(registry, namespace, catalogKey, releaseVersion, releaseFingerprint)

    override fun toString(): String = "$registry~$namespace/$catalogKey@${releaseVersion ?: draftName?.let { "draft '$it'" } ?: "unpinned"}"

    companion object {
        fun of(wire: CatalogDependency): CatalogDependencyDeclaration = CatalogDependencyDeclaration(
            wire.registry,
            wire.namespace,
            wire.catalogKey,
            wire.releaseVersion,
            wire.releaseFingerprint,
        )
    }
}

/** A release of a dependency, as a declaration pins it. */
data class CatalogDependencyRelease(val version: String, val fingerprint: String)

/**
 * The SQL of declared dependencies and the pins of each release. Callers own the
 * transaction: every method takes the handle it runs on.
 */
@Component
class CatalogDependencyStore {

    fun declarations(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT d.registry, d.namespace, d.dependency_key, d.release_version, d.release_fingerprint,
                   dv.version_key AS draft_key, $DRAFT_LABEL AS draft_name
            FROM catalog_dependencies d
            LEFT JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.dependency_version_id
            WHERE d.tenant_key = :t AND d.version_id = ${versionAt("t", "c")}
            ORDER BY d.position
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .map { rs, _ -> declarationOf(rs) }
        .list()

    private fun declarationOf(rs: java.sql.ResultSet) = CatalogDependencyDeclaration(
        rs.getString("registry"),
        rs.getString("namespace"),
        rs.getString("dependency_key"),
        rs.getString("release_version"),
        rs.getString("release_fingerprint"),
        rs.getString("draft_key"),
        rs.getString("draft_name"),
    )

    /** The declarations of draft [versionId], by its internal id, whatever the running message targets. */
    fun declarationsOf(handle: Handle, tenantKey: TenantKey, versionId: java.util.UUID): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT d.registry, d.namespace, d.dependency_key, d.release_version, d.release_fingerprint,
                   dv.version_key AS draft_key, $DRAFT_LABEL AS draft_name
            FROM catalog_dependencies d
            LEFT JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.dependency_version_id
            WHERE d.tenant_key = :t AND d.version_id = :v
            ORDER BY d.position
            """,
        )
        .bind("t", tenantKey)
        .bind("v", versionId)
        .map { rs, _ -> declarationOf(rs) }
        .list()

    /** Replaces the declarations of [catalogKey] with [declarations], in that order. */
    fun replace(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, declarations: List<CatalogDependencyDeclaration>) {
        handle.createUpdate("DELETE FROM catalog_dependencies WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .execute()
        if (declarations.isEmpty()) return
        val batch = handle.prepareBatch(
            """
            INSERT INTO catalog_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, position,
                                              release_version, release_fingerprint, dependency_version_id)
            VALUES (:t, ${catalogAt("t", "c")}, ${versionAt("t", "c")}, :registry, :namespace, :key, :position, :releaseVersion, :releaseFingerprint,
                    (SELECT dv.version_id FROM catalog_versions dv
                     JOIN catalogs dc ON dc.tenant_key = dv.tenant_key AND dc.catalog_id = dv.catalog_id
                     WHERE dv.tenant_key = :t AND dv.kind = 'draft' AND dv.version_key = :draftKey
                       AND dc.registry = :registry AND dc.namespace = :namespace AND dc.catalog_key = :key))
            """,
        )
        declarations.forEachIndexed { position, declaration ->
            batch.bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("registry", declaration.registry)
                .bind("namespace", declaration.namespace)
                .bind("key", declaration.catalogKey)
                .bind("position", position)
                .bind("releaseVersion", declaration.releaseVersion)
                .bind("releaseFingerprint", declaration.releaseFingerprint)
                .bind("draftKey", declaration.draftId)
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
                                                      position, release_version, release_fingerprint)
            VALUES (:t, ${catalogAt("t", "c")}, :version, :registry, :namespace, :key, :position, :releaseVersion, :releaseFingerprint)
            """,
        )
        pins.forEachIndexed { position, pin ->
            batch.bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("registry", pin.registry)
                .bind("namespace", pin.namespace)
                .bind("key", pin.catalogKey)
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
            SELECT registry, namespace, dependency_key, release_version, release_fingerprint
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
     * The releases a declaration may lock to: those of the catalog it names whose fingerprint names
     * its algorithm, which a pin copies. A release installed with a legacy fingerprint has a bare one
     * and is not a candidate.
     */
    fun candidates(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): List<CatalogDependencyRelease> {
        val catalog = catalogNamed(handle, tenantKey, declaration) ?: return emptyList()
        return handle
            .createQuery("SELECT version, fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bind("c", catalog)
            .map { rs, _ -> CatalogDependencyRelease(rs.getString("version"), rs.getString("fingerprint")) }
            .list()
            .filter { QualifiedCatalogFingerprint.parse(it.fingerprint) != null }
    }

    /** The highest release of the catalog [declaration] names that can be pinned, or null when there is none. */
    fun highest(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): CatalogDependencyRelease? = candidates(handle, tenantKey, declaration)
        .mapNotNull { release -> Semver.parse(release.version)?.let { release to it } }
        .maxWithOrNull { a, b -> a.second.compareTo(b.second) }
        ?.first

    /** The release [version] of the catalog [declaration] names, when the tenant has it and it can be pinned. */
    fun release(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration, version: String): CatalogDependencyRelease? = candidates(handle, tenantKey, declaration).firstOrNull { it.version == version }

    /**
     * The open draft [draftKey] of the catalog [declaration] names, by its internal `version_id`, with
     * its label; null when that catalog has no such draft.
     */
    fun draftOf(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration, draftKey: String): Pair<java.util.UUID, String>? = handle
        .createQuery(
            """
            SELECT dv.version_id, $DRAFT_LABEL AS label FROM catalog_versions dv
            JOIN catalogs dc ON dc.tenant_key = dv.tenant_key AND dc.catalog_id = dv.catalog_id
            WHERE dv.tenant_key = :t AND dv.kind = 'draft' AND dv.version_key = :k
              AND dc.registry = :registry AND dc.namespace = :namespace AND dc.catalog_key = :key
            """,
        )
        .bind("t", tenantKey)
        .bind("k", draftKey)
        .bind("registry", declaration.registry)
        .bind("namespace", declaration.namespace)
        .bind("key", declaration.catalogKey)
        .map { rs, _ -> rs.getObject("version_id", java.util.UUID::class.java) to rs.getString("label") }
        .findOne()
        .orElse(null)

    /** The key and label of the next version of the catalog [declaration] names, or null when it has none. */
    fun nextVersionOf(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): Pair<String, String>? = handle
        .createQuery(
            """
            SELECT dv.version_key, $DRAFT_LABEL AS label FROM catalog_versions dv
            JOIN catalogs dc ON dc.tenant_key = dv.tenant_key AND dc.catalog_id = dv.catalog_id
            WHERE dv.tenant_key = :t AND dv.kind = 'draft' AND dv.is_next
              AND dc.registry = :registry AND dc.namespace = :namespace AND dc.catalog_key = :key
            """,
        )
        .bind("t", tenantKey)
        .bind("registry", declaration.registry)
        .bind("namespace", declaration.namespace)
        .bind("key", declaration.catalogKey)
        .map { rs, _ -> rs.getString("version_key") to rs.getString("label") }
        .findOne()
        .orElse(null)

    /**
     * Every declaration, in any draft of the tenant, that names draft [versionId] now pins
     * [release] instead: what releasing the named draft does (contract ADR 0002).
     */
    fun pinDeclarationsOf(handle: Handle, tenantKey: TenantKey, versionId: java.util.UUID, release: CatalogDependencyRelease) {
        handle.createUpdate(
            """
            UPDATE catalog_dependencies SET dependency_version_id = NULL, release_version = :v, release_fingerprint = :f
            WHERE tenant_key = :t AND dependency_version_id = :d
            """,
        )
            .bind("t", tenantKey)
            .bind("d", versionId)
            .bind("v", release.version)
            .bind("f", release.fingerprint)
            .execute()
    }

    /** The catalogs, by key and draft label, whose drafts name draft [versionId] in a declaration. */
    fun namedBy(handle: Handle, tenantKey: TenantKey, versionId: java.util.UUID): List<String> = handle
        .createQuery(
            """
            SELECT DISTINCT c.catalog_key || ' (' || $DECLARING_LABEL || ')' AS who
            FROM catalog_dependencies d
            JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.version_id = d.version_id
            JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
            WHERE d.tenant_key = :t AND d.dependency_version_id = :d
            ORDER BY who
            """,
        )
        .bind("t", tenantKey)
        .bind("d", versionId)
        .mapTo(String::class.java)
        .list()

    /**
     * Whether [declaration]'s pin still holds: the tenant still has the pinned release, with the
     * fingerprint the pin recorded.
     */
    fun pinHolds(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): Boolean {
        val version = declaration.releaseVersion ?: return false
        return release(handle, tenantKey, declaration, version)?.fingerprint == declaration.releaseFingerprint
    }

    private companion object {
        /** A draft's label over alias `dv`: its name, or "Next version" for the next version. */
        const val DRAFT_LABEL = "CASE WHEN dv.is_next THEN 'Next version' ELSE dv.name END"

        /** The same over alias `v`, for the declaring draft. */
        const val DECLARING_LABEL = "CASE WHEN v.is_next THEN 'Next version' ELSE v.name END"
    }
}
