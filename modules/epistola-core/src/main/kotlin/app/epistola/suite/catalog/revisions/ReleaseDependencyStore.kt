// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogContent
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

    /** Records the pins of one release. Called inside the release transaction. */
    fun record(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String, pins: Map<CatalogKey, String>) {
        for ((dependency, dependencyVersion) in pins) {
            handle.createUpdate(
                """
                INSERT INTO release_dependencies (tenant_key, catalog_key, version, dependency_catalog_key, dependency_version)
                VALUES (:t, :c, :version, :dependency, :dependencyVersion)
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("dependency", dependency)
                .bind("dependencyVersion", dependencyVersion)
                .execute()
        }
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
