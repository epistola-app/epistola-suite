// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.LATEST_RELEASE_ORDER
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component

/**
 * Which release of another catalog a release renders with.
 *
 * Sole owner of the `release_dependencies` SQL. A template may use a theme, font, image, stencil or
 * code list from a different catalog. When a catalog is released, each catalog it references is
 * pinned at the release that is latest at that moment, and rendering the release reads exactly
 * that one: never the other catalog's working copy, and never whatever is latest by the time the
 * release is rendered. A lockfile without a declaration; pins chosen by the author come later, and
 * only change which release is written here.
 *
 * Only releases that kept their content can be pinned, because a pinned release is read to render.
 */
/** One recorded dependency of a release: [catalog] at [version], used directly or through another one. */
data class Pin(val catalog: CatalogKey, val version: String, val direct: Boolean)

@Component
class ReleaseDependencyStore {

    /**
     * The catalogs [content] renders with besides its own: those its manifest declares, plus the
     * catalog of the tenant's default theme when a template has no theme of its own and therefore
     * falls back to it.
     *
     * @param tenantDefaultThemeCatalog the catalog of the tenant's default theme, or null when the
     *   tenant has none -- a template without a theme then renders with engine defaults.
     */
    fun referencedCatalogs(
        content: CatalogContent,
        ownCatalog: CatalogKey,
        tenantDefaultThemeCatalog: CatalogKey?,
    ): Set<CatalogKey> {
        val referenced = content.dependencies.orEmpty().mapTo(LinkedHashSet()) { CatalogKey.of(it.catalogKey()) }
        val fallsBackToTenantTheme = content.resourceDetails.values
            .map { it.resource }
            .filterIsInstance<app.epistola.catalog.protocol.TemplateResource>()
            .any { it.themeId == null }
        if (fallsBackToTenantTheme && tenantDefaultThemeCatalog != null) referenced += tenantDefaultThemeCatalog
        referenced -= ownCatalog
        return referenced
    }

    /**
     * Resolves each referenced catalog to its latest release that kept its content.
     *
     * @throws ValidationException naming every catalog that has no such release, so the author knows
     *   which to release first.
     */
    fun resolveLatest(handle: Handle, tenantKey: TenantKey, catalogs: Set<CatalogKey>): Map<CatalogKey, String> {
        val resolved = catalogs.associateWith { latestRetainedRelease(handle, tenantKey, it) }
        val missing = resolved.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            throw ValidationException(
                "dependencies",
                "This catalog uses resources from ${missing.joinToString { "'${it.value}'" }}, which has no release " +
                    "that kept its content. Release ${if (missing.size == 1) "it" else "them"} first.",
            )
        }
        return resolved.mapValues { (_, version) -> version!! }
    }

    /**
     * The whole set of releases [ownCatalog] renders with, starting from the catalogs it references
     * directly ([direct]) and following each dependency's own recorded dependencies.
     *
     * Flattened, one release per catalog: a document never mixes two releases of one catalog. Where
     * two dependencies recorded different releases of the same catalog, the highest wins — what
     * semantic versioning promises is compatible, and what the release check is there to confirm.
     * A direct dependency is recorded at that catalog's latest release, so it is never lowered by a
     * dependency that recorded an older one. The release's own catalog is never a dependency, so a
     * catalog depending back on it ends the walk rather than looping.
     */
    fun closure(handle: Handle, tenantKey: TenantKey, ownCatalog: CatalogKey, direct: Map<CatalogKey, String>): List<Pin> {
        val chosen = LinkedHashMap<CatalogKey, Pin>()
        direct.forEach { (catalog, version) -> chosen[catalog] = Pin(catalog, version, direct = true) }
        val pending = ArrayDeque(chosen.values.toList())
        while (pending.isNotEmpty()) {
            val pin = pending.removeFirst()
            for ((catalog, version) in pinsOf(handle, tenantKey, pin.catalog, pin.version)) {
                if (catalog == ownCatalog) continue
                val current = chosen[catalog]
                if (current == null || (!current.direct && isHigher(version, current.version))) {
                    val next = Pin(catalog, version, direct = current?.direct ?: false)
                    chosen[catalog] = next
                    pending += next
                }
            }
        }
        return chosen.values.toList()
    }

    /** Records the pins of one release. Called inside the release transaction. */
    fun record(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, pins: List<Pin>) {
        for (pin in pins) {
            handle.createUpdate(
                """
                INSERT INTO release_dependencies (tenant_key, catalog_key, version, dependency_catalog_key, dependency_version, direct)
                VALUES (:t, :c, :version, :dependency, :dependencyVersion, :direct)
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("dependency", pin.catalog)
                .bind("dependencyVersion", pin.version)
                .bind("direct", pin.direct)
                .execute()
        }
    }

    private fun isHigher(candidate: String, current: String): Boolean {
        val a = SemVer.parseOrNull(candidate)
        val b = SemVer.parseOrNull(current)
        return if (a != null && b != null) a > b else candidate > current
    }

    /** The pins of one release: dependency catalog to the release it renders with. */
    fun pinsOf(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): Map<CatalogKey, String> = handle
        .createQuery(
            """
            SELECT dependency_catalog_key, dependency_version FROM release_dependencies
            WHERE tenant_key = :t AND catalog_key = :c AND version = :version
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("version", version)
        .map { rs, _ -> CatalogKey.of(rs.getString("dependency_catalog_key")) to rs.getString("dependency_version") }
        .list()
        .toMap()

    /** The releases that render with [version] of [catalogKey], as `catalog@version`. */
    fun dependents(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): List<String> = handle
        .createQuery(
            """
            SELECT catalog_key, version FROM release_dependencies
            WHERE tenant_key = :t AND dependency_catalog_key = :c AND dependency_version = :version
            ORDER BY catalog_key, version
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .bind("version", version)
        .map { rs, _ -> "${rs.getString("catalog_key")}@${rs.getString("version")}" }
        .list()

    /** The latest release of [catalogKey] that kept its content, or null when none has. */
    fun latestRetainedRelease(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = handle
        .createQuery(
            """
            SELECT version FROM catalog_releases
            WHERE tenant_key = :t AND catalog_key = :c AND content_retained
            $LATEST_RELEASE_ORDER
            LIMIT 1
            """,
        )
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .mapTo(String::class.java)
        .findOne()
        .orElse(null)
}

private fun DependencyRef.catalogKey(): String = when (this) {
    is DependencyRef.Theme -> catalogKey
    is DependencyRef.Font -> catalogKey
    is DependencyRef.Image -> catalogKey
    is DependencyRef.Stencil -> catalogKey
    is DependencyRef.CodeList -> catalogKey
}
