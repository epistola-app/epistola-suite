// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.canonical.QualifiedCatalogFingerprint
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.isNext
import app.epistola.suite.catalog.releases.CatalogDraft
import app.epistola.suite.catalog.requireWritableVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.semver4j.Semver
import org.springframework.stereotype.Component

/**
 * A catalog this catalog depends on (contract ADR 0003): named by registry, namespace and key, and
 * pinned to the exact release [releaseVersion] with its [releaseFingerprint]. In a draft it may name
 * a draft of that catalog instead ([draftId], its public id, and [draftName]), to build on work not
 * released yet. All are null for a declaration that pins nothing yet (one migrated from wire v7).
 * Releasing refuses either until it pins a release.
 *
 * [alias] is what the catalog's content names the dependency by (contract ADR 0003, ADR 0034): a
 * reference into it names the alias and a resource key. This installation tells catalogs apart by
 * key within a tenant until ADR 0034's phase 4, and stores references by the catalog's key, so a
 * dependency's alias is always its [catalogKey], the contract's default; an archive that declares
 * another is refused on receipt ([DependencyAliases.unsupported]).
 */
data class CatalogDependencyDeclaration(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String? = null,
    val releaseFingerprint: String? = null,
    val draftId: String? = null,
    val draftName: String? = null,
    val alias: String = catalogKey,
) {
    val identity: Triple<String, String, String> get() = Triple(registry, namespace, catalogKey)

    val pinned: Boolean get() = releaseVersion != null

    /** It names a draft of the dependency, which a release cannot pin. */
    val namesDraft: Boolean get() = draftId != null

    /** A release never pins a draft, so the wire form of a declaration naming one pins nothing. */
    fun toWire(): CatalogDependency = CatalogDependency(registry, namespace, catalogKey, releaseVersion, releaseFingerprint, alias)

    /** The catalog this names, as [tenantKey]'s address of it (ADR 0034, section 9). */
    fun catalogIn(tenantKey: TenantKey): CatalogId = CatalogId(namespace, CatalogKey.of(catalogKey), app.epistola.suite.common.ids.TenantId(tenantKey))

    override fun toString(): String = "$registry~$namespace/$catalogKey@${releaseVersion ?: draftName?.let { "draft '$it'" } ?: "unpinned"}"

    companion object {
        fun of(wire: CatalogDependency): CatalogDependencyDeclaration = CatalogDependencyDeclaration(
            wire.registry,
            wire.namespace,
            wire.catalogKey,
            wire.releaseVersion,
            wire.releaseFingerprint,
            alias = wire.alias,
        )
    }
}

/**
 * A release of a dependency, as a declaration pins it: its number and the fingerprint a pin copies,
 * its content hash (ADR 0034), or the fingerprint it was recorded with while it has none.
 */
data class CatalogDependencyRelease(val version: String, val fingerprint: String)

/**
 * The SQL of declared dependencies and the pins of each release. Callers own the
 * transaction: every method takes the handle it runs on.
 */
@Component
class CatalogDependencyStore(private val identity: app.epistola.suite.catalog.revisions.ReleaseIdentity) {

    fun declarations(handle: Handle, catalog: CatalogId): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT d.registry, d.namespace, d.dependency_key, d.alias, d.release_version, d.release_fingerprint,
                   dv.version_key AS draft_key, $DRAFT_LABEL AS draft_name
            FROM catalog_version_dependencies d
            LEFT JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.target_version_id AND dv.state = 'draft'
            WHERE d.tenant_key = :t AND d.version_id = ${versionAt("t", "c")}
            ORDER BY d.position
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
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
        rs.getString("alias"),
    )

    /** The declarations of draft [versionId], by its internal id, whatever the running message targets. */
    fun declarationsOf(handle: Handle, tenantKey: TenantKey, versionId: java.util.UUID): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT d.registry, d.namespace, d.dependency_key, d.alias, d.release_version, d.release_fingerprint,
                   dv.version_key AS draft_key, $DRAFT_LABEL AS draft_name
            FROM catalog_version_dependencies d
            LEFT JOIN catalog_versions dv ON dv.tenant_key = d.tenant_key AND dv.version_id = d.target_version_id AND dv.state = 'draft'
            WHERE d.tenant_key = :t AND d.version_id = :v
            ORDER BY d.position
            """,
        )
        .bind("t", tenantKey)
        .bind("v", versionId)
        .map { rs, _ -> declarationOf(rs) }
        .list()

    /**
     * Replaces the declarations of [catalogKey] with [declarations], in that order.
     *
     * @throws ValidationException when a declaration it adds names a catalog that already uses
     *   [catalogKey]: catalogs never use each other. Every write that starts using another catalog
     *   declares it here, in its own transaction, so the write is refused with it.
     */
    fun replace(handle: Handle, catalog: CatalogId, declarations: List<CatalogDependencyDeclaration>) {
        handle.requireWritableVersion(catalog)
        // Only a change counts: the same declarations again leave the version's revision as it is.
        fun CatalogDependencyDeclaration.what() = listOf(registry, namespace, this.catalogKey, alias, releaseVersion, releaseFingerprint, draftId)
        val current = declarations(handle, catalog)
        if (current.map { it.what() } == declarations.map { it.what() }) return
        requireNoCycle(handle, catalog, declarations.filter { new -> current.none { it.identity == new.identity } })
        handle.createUpdate("DELETE FROM catalog_version_dependencies WHERE tenant_key = :t AND version_id = ${versionAt("t", "c")}")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .execute()
        if (declarations.isEmpty()) return
        val batch = handle.prepareBatch(
            """
            INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias, position,
                                                      release_version, release_fingerprint, target_version_id)
            VALUES (:t, ${catalogAt("t", "c")}, ${versionAt("t", "c")}, :registry, :namespace, :key, :alias, :position, :releaseVersion, :releaseFingerprint,
                    $TARGET)
            """,
        )
        declarations.forEachIndexed { position, declaration ->
            batch.bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
                .bind("registry", declaration.registry)
                .bind("namespace", declaration.namespace)
                .bind("key", declaration.catalogKey)
                .bind("alias", declaration.alias)
                .bind("position", position)
                .bind("releaseVersion", declaration.releaseVersion)
                .bind("releaseFingerprint", declaration.releaseFingerprint)
                .bind("draftKey", declaration.draftId)
                .add()
        }
        batch.execute()
    }

    /**
     * Refuses [added] when one names a catalog that already uses [catalogKey], directly or through
     * others: catalogs never use each other. 2.0 could not release them, since each would have to
     * pin the other's release first, and installing, restoring and the upgrade all order catalogs by
     * what they use. Judged by what every other version declares (each draft of each catalog, and each
     * subscribed catalog's installed version), so a parallel draft cannot close the loop either.
     */
    private fun requireNoCycle(handle: Handle, catalog: CatalogId, added: List<CatalogDependencyDeclaration>) {
        val targets = added.map { it.catalogKey }.filter { it != catalog.key.value }.toSet()
        if (targets.isEmpty()) return
        val uses = handle.createQuery(
            """
            SELECT DISTINCT c.catalog_key AS user_key, dc.catalog_key AS used_key
            FROM catalog_version_dependencies d
            JOIN catalog_versions dvv ON dvv.tenant_key = d.tenant_key AND dvv.version_id = d.version_id AND dvv.state = 'draft'
            JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
            JOIN catalogs dc ON dc.tenant_key = d.tenant_key AND dc.registry = d.registry AND dc.namespace = d.namespace
                            AND dc.catalog_key = d.dependency_key
            WHERE d.tenant_key = :t AND d.version_id <> ${versionAt("t", "c")}
            """,
        )
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> rs.getString("user_key") to rs.getString("used_key") }
            .list()
            .groupBy({ it.first }, { it.second })
        for (target in targets.sorted()) {
            val path = pathBetween(target, catalog.key.value, uses) ?: continue
            throw ValidationException(
                "dependencies",
                "Catalog '${catalog.key.value}' cannot use '$target': '$target' already uses '${catalog.key.value}' " +
                    "(${(path).joinToString(" uses ") { "'$it'" }}), and catalogs never use each other. " +
                    "Move what they share into one of them, or into a catalog they both use.",
                ValidationCode.CATALOG_DEPENDENCY_INVALID,
            )
        }
    }

    /** The catalogs from [from] to [to] along [uses], both included; null when [to] is not reached. */
    private fun pathBetween(from: String, to: String, uses: Map<String, List<String>>): List<String>? {
        val previous = mutableMapOf(from to from)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at == to) return generateSequence(to) { node -> previous.getValue(node).takeIf { node != from } }.toList().reversed()
            uses[at].orEmpty().sorted().filter { it !in previous }.forEach {
                previous[it] = at
                queue.addLast(it)
            }
        }
        return null
    }

    /**
     * Records the declarations release [version] of [catalogKey] was cut with, each with its pin: its
     * row's declarations (ADR 0036), in place of what the draft it froze declared.
     */
    fun recordRelease(handle: Handle, catalog: CatalogId, version: String, pins: List<CatalogDependencyDeclaration>) {
        val released = "(SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :version AND state = 'released')"
        handle.createUpdate("DELETE FROM catalog_version_dependencies WHERE tenant_key = :t AND version_id = $released")
            .bind("t", catalog.tenantKey)
            .bindCatalog("c", catalog)
            .bind("version", version)
            .execute()
        if (pins.isEmpty()) return
        val batch = handle.prepareBatch(
            """
            INSERT INTO catalog_version_dependencies (tenant_key, catalog_id, version_id, registry, namespace, dependency_key, alias,
                                                      position, release_version, release_fingerprint, target_version_id)
            VALUES (:t, ${catalogAt("t", "c")}, $released, :registry, :namespace, :key, :alias, :position, :releaseVersion, :releaseFingerprint, $TARGET)
            """,
        )
        pins.forEachIndexed { position, pin ->
            batch.bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
                .bind("version", version)
                .bind("registry", pin.registry)
                .bind("namespace", pin.namespace)
                .bind("key", pin.catalogKey)
                .bind("alias", pin.alias)
                .bind("position", position)
                .bind("releaseVersion", pin.releaseVersion)
                .bind("releaseFingerprint", pin.releaseFingerprint)
                .bind("draftKey", null as String?)
                .add()
        }
        batch.execute()
    }

    /** The declarations release [version] of [catalogKey] was cut with, each with its pin. */
    fun releasePins(handle: Handle, catalog: CatalogId, version: String): List<CatalogDependencyDeclaration> = handle
        .createQuery(
            """
            SELECT d.registry, d.namespace, d.dependency_key, d.alias, d.release_version, d.release_fingerprint
            FROM catalog_version_dependencies d
            JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.version_id = d.version_id
            WHERE d.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.version = :version AND v.state = 'released'
            ORDER BY d.position
            """,
        )
        .bind("t", catalog.tenantKey)
        .bindCatalog("c", catalog)
        .bind("version", version)
        .map { rs, _ ->
            CatalogDependencyDeclaration(
                rs.getString("registry"),
                rs.getString("namespace"),
                rs.getString("dependency_key"),
                rs.getString("release_version"),
                rs.getString("release_fingerprint"),
                alias = rs.getString("alias"),
            )
        }
        .list()

    /**
     * The tenant's catalog that [declaration] names, by registry, namespace and key, or null when
     * the tenant has none: a catalog with the same key in another namespace is another catalog.
     */
    fun catalogNamed(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): CatalogId? = handle
        .createQuery("SELECT catalog_key FROM catalogs WHERE tenant_key = :t AND registry = :registry AND namespace = :namespace AND catalog_key = :key")
        .bind("t", tenantKey)
        .bind("registry", declaration.registry)
        .bind("namespace", declaration.namespace)
        .bind("key", declaration.catalogKey)
        .mapTo(String::class.java)
        .findOne()
        .map { CatalogId(declaration.namespace, CatalogKey.of(it), app.epistola.suite.common.ids.TenantId(tenantKey)) }
        .orElse(null)

    /**
     * The releases a declaration may lock to: those of the catalog it names whose fingerprint names
     * its algorithm, which a pin copies. A release installed with a legacy fingerprint has a bare one
     * and is not a candidate.
     */
    fun candidates(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): List<CatalogDependencyRelease> = recordedCandidates(handle, tenantKey, declaration)
        .map { CatalogDependencyRelease(it.version, it.contentHash) }

    private fun recordedCandidates(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): List<app.epistola.suite.catalog.revisions.ReleaseIdentity.Recorded> {
        val catalog = catalogNamed(handle, tenantKey, declaration) ?: return emptyList()
        return handle
            .createQuery("SELECT version, fingerprint, content_hash FROM catalog_versions WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
            .bind("t", tenantKey)
            .bindCatalog("c", catalog)
            .map { rs, _ -> app.epistola.suite.catalog.revisions.ReleaseIdentity.Recorded(rs.getString("version"), rs.getString("fingerprint"), rs.getString("content_hash")) }
            .list()
            .filter { QualifiedCatalogFingerprint.parse(it.fingerprint) != null }
    }

    /** [recorded] as a pin takes it: by its content hash. */
    @Suppress("UNUSED_PARAMETER")
    private fun pinOf(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration, recorded: app.epistola.suite.catalog.revisions.ReleaseIdentity.Recorded): CatalogDependencyRelease = CatalogDependencyRelease(recorded.version, recorded.contentHash)

    /** The highest release of the catalog [declaration] names that can be pinned, or null when there is none. */
    fun highest(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): CatalogDependencyRelease? = recordedCandidates(handle, tenantKey, declaration)
        .mapNotNull { release -> Semver.parse(release.version)?.let { release to it } }
        .maxWithOrNull { a, b -> a.second.compareTo(b.second) }
        ?.first
        ?.let { pinOf(handle, tenantKey, declaration, it) }

    /** The release [version] of the catalog [declaration] names, when the tenant has it and it can be pinned. */
    fun release(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration, version: String): CatalogDependencyRelease? = recordedCandidates(handle, tenantKey, declaration)
        .firstOrNull { it.version == version }
        ?.let { pinOf(handle, tenantKey, declaration, it) }

    /**
     * The open draft [draftKey] of the catalog [declaration] names, by its internal `version_id`, with
     * its label; null when that catalog has no such draft.
     */
    fun draftOf(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration, draftKey: String): Pair<java.util.UUID, String>? = handle
        .createQuery(
            """
            SELECT dv.version_id, $DRAFT_LABEL AS label FROM catalog_versions dv
            JOIN catalogs dc ON dc.tenant_key = dv.tenant_key AND dc.catalog_id = dv.catalog_id
            WHERE dv.tenant_key = :t AND dv.state = 'draft' AND dv.version_key = :k
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
            WHERE dv.tenant_key = :t AND dv.state = 'draft' AND ${isNext("dv")}
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
     * [declaration] as publishing reads it. One that pins no release and names no draft (declared by
     * name, as the 2.0 upgrade declares what a 1.x catalog used) is resolved when its catalog is
     * released: to the highest release of the catalog it names, which the release pins; or, when that
     * catalog has none yet, to its next version, which publishing publishes first. So it names that
     * version here; anything else is returned as it is.
     */
    fun asPublished(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): CatalogDependencyDeclaration {
        if (declaration.pinned || declaration.namesDraft || highest(handle, tenantKey, declaration) != null) return declaration
        val (draftId, draftName) = nextVersionOf(handle, tenantKey, declaration) ?: return declaration
        return declaration.copy(draftId = draftId, draftName = draftName)
    }

    /**
     * Every declaration, in any draft of the tenant, that names draft [versionId] now pins
     * [release] instead: what releasing the named draft does (contract ADR 0002).
     */
    fun pinDeclarationsOf(handle: Handle, tenantKey: TenantKey, versionId: java.util.UUID, release: CatalogDependencyRelease) {
        handle.createUpdate(
            """
            UPDATE catalog_version_dependencies SET release_version = :v, release_fingerprint = :f
            WHERE tenant_key = :t AND target_version_id = :d AND release_version IS NULL
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
            FROM catalog_version_dependencies d
            JOIN catalog_versions v ON v.tenant_key = d.tenant_key AND v.version_id = d.version_id AND v.state = 'draft'
            JOIN catalogs c ON c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id
            WHERE d.tenant_key = :t AND d.target_version_id = :d AND d.release_version IS NULL
            ORDER BY who
            """,
        )
        .bind("t", tenantKey)
        .bind("d", versionId)
        .mapTo(String::class.java)
        .list()

    /**
     * Whether [declaration]'s pin still holds: the tenant still has the pinned release, the one the
     * pin's fingerprint names, by its content hash or the fingerprint it was recorded with
     * ([app.epistola.suite.catalog.revisions.ReleaseIdentity.isRelease]), so a pin taken as V5 and a
     * release received as V6, or the other way round, are one release when their content is.
     */
    fun pinHolds(handle: Handle, tenantKey: TenantKey, declaration: CatalogDependencyDeclaration): Boolean {
        val version = declaration.releaseVersion ?: return false
        val pin = declaration.releaseFingerprint ?: return false
        val catalog = catalogNamed(handle, tenantKey, declaration) ?: return false
        val recorded = recordedCandidates(handle, tenantKey, declaration).firstOrNull { it.version == version } ?: return false
        return identity.isRelease(handle, catalog, recorded, pin)
    }

    private companion object {
        /**
         * The version a declaration names, when it is here: the draft its `:draftKey` names, else the
         * release it pins (`:releaseVersion`) of the catalog `:registry`/`:namespace`/`:key`.
         */
        const val TARGET = """COALESCE(
                (SELECT dv.version_id FROM catalog_versions dv
                 JOIN catalogs dc ON dc.tenant_key = dv.tenant_key AND dc.catalog_id = dv.catalog_id
                 WHERE dv.tenant_key = :t AND dv.state = 'draft' AND dv.version_key = :draftKey
                   AND dc.registry = :registry AND dc.namespace = :namespace AND dc.catalog_key = :key),
                (SELECT rv.version_id FROM catalog_versions rv
                 JOIN catalogs rc ON rc.tenant_key = rv.tenant_key AND rc.catalog_id = rv.catalog_id
                 WHERE rv.tenant_key = :t AND rv.state = 'released' AND rv.version = :releaseVersion
                   AND rc.registry = :registry AND rc.namespace = :namespace AND rc.catalog_key = :key))"""

        /** A draft's label over alias `dv`: its name, or "Next release" for the next release. */
        val DRAFT_LABEL = "CASE WHEN ${isNext("dv")} THEN '${CatalogDraft.NEXT_LABEL}' ELSE dv.name END"

        /** The same over alias `v`, for the declaring draft. */
        val DECLARING_LABEL = "CASE WHEN ${isNext("v")} THEN '${CatalogDraft.NEXT_LABEL}' ELSE v.name END"
    }
}
