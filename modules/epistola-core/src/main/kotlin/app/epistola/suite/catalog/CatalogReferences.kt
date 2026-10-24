// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The one resolver from a reference to the catalog it means (ADR 0034, section 9). Content refers to
 * another catalog by alias, `{alias, resource key}`, and to its own without one; the alias is the
 * catalog's key here. [resolve] gives `from` itself without an alias (or with its own key), else the
 * catalog `from`'s dependency row for that alias names. Everything that follows a reference goes
 * through it: theme resolution, stencil expansion (with `from` the stencil's catalog), attribute code
 * lists, usages, the graph, rendering.
 */
@Component
class CatalogReferences(private val jdbi: Jdbi) {
    /** The catalog [alias] means in [from]'s current version (the version the running message targets); null when it declares none. */
    fun resolve(from: CatalogId, alias: String?): CatalogId? = if (own(from, alias)) from else jdbi.withHandle<CatalogId?, Exception> { resolve(it, from, alias) }

    /** [resolve] on [handle]. */
    fun resolve(handle: Handle, from: CatalogId, alias: String?): CatalogId? {
        if (own(from, alias)) return from
        return handle.createQuery(
            """
            SELECT d.namespace, d.dependency_key FROM catalog_dependencies d
            WHERE d.tenant_key = :t AND d.version_id = ${versionAt("t", "from")} AND d.alias = :alias
            """,
        )
            .bind("t", from.tenantKey)
            .bindCatalog("from", from)
            .bind("alias", alias)
            .map { rs, _ -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("dependency_key")), from.tenantId) }
            .findOne()
            .orElse(null)
    }

    /** The catalog [alias] means in release [version] of [from]: by the release's own pins. */
    fun resolveInRelease(handle: Handle, from: CatalogId, version: String, alias: String?): CatalogId? {
        if (own(from, alias)) return from
        return handle.createQuery(
            """
            SELECT d.namespace, d.dependency_key FROM catalog_release_dependencies d
            WHERE d.tenant_key = :t AND d.catalog_id = ${catalogAt("t", "from")} AND d.version = :v AND d.alias = :alias
            """,
        )
            .bind("t", from.tenantKey)
            .bindCatalog("from", from)
            .bind("v", version)
            .bind("alias", alias)
            .map { rs, _ -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("dependency_key")), from.tenantId) }
            .findOne()
            .orElse(null)
    }

    /**
     * [alias] as content being received into [from] means it: by the [declarations] it arrives with
     * (stored after its resources), else by what [from] declares now, else, for content that predates
     * declarations, the one catalog of the tenant with that key. Null when none answers.
     */
    fun resolveReceived(from: CatalogId, alias: String?, declarations: List<CatalogDependencyDeclaration>?): CatalogId? {
        if (own(from, alias)) return from
        declarations?.let { resolve(from, alias, it) }?.let { return it }
        return resolve(from, alias) ?: uniqueByKey(from, requireNotNull(alias))
    }

    /** The one catalog of [from]'s tenant with key [key], or null when it has none or several. */
    fun uniqueByKey(from: CatalogId, key: String): CatalogId? = jdbi.withHandle<CatalogId?, Exception> { handle ->
        handle.createQuery("SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_key = :k")
            .bind("t", from.tenantKey)
            .bind("k", key)
            .mapTo(String::class.java)
            .list()
            .singleOrNull()
            ?.let { CatalogId(it, CatalogKey.of(key), from.tenantId) }
    }

    companion object {
        /** Whether [alias] names [from] itself: no alias, or its own key. */
        fun own(from: CatalogId, alias: String?): Boolean = alias == null || alias == from.key.value

        /** [resolve] against [declarations] not stored yet: what an archive being imported declares. */
        fun resolve(from: CatalogId, alias: String?, declarations: List<CatalogDependencyDeclaration>): CatalogId? = if (own(from, alias)) from else declarations.firstOrNull { it.alias == alias }?.catalogIn(from.tenantKey)
    }
}

/**
 * SQL for the `catalog_id` the alias bound as [aliasParam] means from the catalog bound as
 * [fromParam] ([bindCatalog]), in its current version: [CatalogReferences.resolve] inside a
 * statement. NULL when the alias is not declared there.
 */
fun referenceAt(tenantParam: String, fromParam: String, aliasParam: String): String = """
    (CASE WHEN CAST(:$aliasParam AS TEXT) IS NULL OR :$aliasParam = :$fromParam THEN ${catalogAt(tenantParam, fromParam)}
     ELSE (SELECT epistola_catalog_id(:$tenantParam, ref.namespace, ref.dependency_key) FROM catalog_dependencies ref
           WHERE ref.tenant_key = :$tenantParam AND ref.version_id = ${versionAt(tenantParam, fromParam)} AND ref.alias = :$aliasParam) END)
"""
