// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogId
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.SqlStatement

/**
 * The opaque `catalog_id` of the catalog a caller names by its address, namespace and key (ADR 0034,
 * section 9), through the database function `epistola_catalog_id`. Bind the address with
 * [bindCatalog]: [catalogParam] is the key and `<catalogParam>_ns` the namespace. None is NULL, which
 * matches no row on a read and violates the NOT NULL `catalog_id` on a write -- so neither silently
 * succeeds.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 * @param catalogParam name of the bound catalog parameter ([bindCatalog]).
 */
fun catalogAt(tenantParam: String, catalogParam: String): String = "epistola_catalog_id(:$tenantParam, :${catalogParam}_ns, :$catalogParam)"

/** Binds [catalog] for [catalogAt]: its key as [name], its namespace as `<name>_ns`; both null for none (an optional filter). */
fun <T : SqlStatement<T>> T.bindCatalog(name: String, catalog: CatalogId?): T = bind(name, catalog?.key?.value).bind("${name}_ns", catalog?.namespace)

/**
 * The `catalog_id`s of the catalogs bound as [listParam] ([bindCatalogs]), for
 * `catalog_id IN ${'$'}{catalogsAt(…)}`.
 */
fun catalogsAt(tenantParam: String, listParam: String): String = "(SELECT epistola_catalog_id(:$tenantParam, a.ns, a.k) FROM unnest(ARRAY[<${listParam}_ns>]::text[], ARRAY[<$listParam>]::text[]) AS a(ns, k))"

/** Binds [catalogs] for [catalogsAt]: their keys as [name], their namespaces as `<name>_ns`, in the same order. */
fun <T : SqlStatement<T>> T.bindCatalogs(name: String, catalogs: Collection<CatalogId>): T = bindList(name, catalogs.map { it.key.value }).bindList("${name}_ns", catalogs.map { it.namespace })

/**
 * The `version_id` of the version a caller means of the catalog it named by key (the
 * `catalog_current_versions` view): the version its transaction targets, when that is a version of
 * this catalog; else its installed release when it is subscribed; else its next version.
 *
 * Content rows belong to a catalog version (contract ADR 0002), so every statement on content names
 * one (`ContentVersionSqlTest`). A message that addresses one version -- the contract's
 * `…/versions/{catalogVersion}/…` paths -- implements
 * [CatalogReleaseScoped][app.epistola.suite.catalog.releases.CatalogReleaseScoped]; the mediator then
 * sets the PostgreSQL setting `epistola.content_version` to that version for the message's
 * transaction ([CatalogReleaseTargeting][app.epistola.suite.catalog.releases.CatalogReleaseTargeting]),
 * and this answers it for that catalog, in the handler and in every message it dispatches. A caller
 * that names none -- the UI, MCP, tenant-wide reads -- gets the current version. A catalog that does
 * not exist yields NULL, which matches no row on a read and violates the NOT NULL `version_id` on a
 * write.
 *
 * @param tenantParam name of the bound tenant parameter.
 * @param catalogParam name of the bound catalog-key parameter.
 */
fun versionAt(tenantParam: String, catalogParam: String): String = "(SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = :$tenantParam AND cv.catalog_id = ${catalogAt(tenantParam, catalogParam)})"

/**
 * A scalar subquery for the row of release [versionParam] of a catalog (ADR 0036: every version is a
 * row of `catalog_versions`), or NULL when there is no such release. For statements that name a
 * release by its number, such as a deployment's.
 */
fun releaseAt(tenantParam: String, catalogParam: String, versionParam: String): String = "(SELECT rv.version_id FROM catalog_versions rv WHERE rv.tenant_key = :$tenantParam AND rv.catalog_id = ${catalogAt(tenantParam, catalogParam)} AND rv.state = 'released' AND rv.version = :$versionParam)"

/**
 * A predicate that the content row aliased [alias] is part of the version its catalog's callers mean
 * (see [versionAt]: the targeted version for its catalog, else the current one). For statements that reach content without a catalog key in hand: tenant-wide reads,
 * and references to a resource by its identity, possibly in another catalog (a template's theme, an
 * attribute's code list, the tenant's default theme). Usable in a `JOIN ... ON` as well as a `WHERE`.
 */
fun inCurrentVersion(alias: String): String = "$alias.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = $alias.tenant_key)"

/** The catalog at [catalog] (see [catalogAt]), or null. */
fun Handle.findCatalog(catalog: CatalogId): Catalog? = createQuery(
    "SELECT * FROM catalogs WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")}",
)
    .bind("tenantKey", catalog.tenantKey)
    .bindCatalog("catalogKey", catalog)
    .mapTo<Catalog>()
    .findOne()
    .orElse(null)

/**
 * The revision of the content row of [table] at [key] (its [keyColumn]) in the version a caller means
 * of [catalog], or null when there is none: what a guarded write that changed nothing compares
 * against ([app.epistola.suite.common.Revisions.refuse]).
 */
fun Handle.contentRevision(table: String, keyColumn: String, catalog: CatalogId, key: Any): Long? = createQuery(
    "SELECT revision FROM $table WHERE tenant_key = :t AND $keyColumn = :k AND version_id = ${versionAt("t", "c")}",
)
    .bind("t", catalog.tenantKey)
    .bindCatalog("c", catalog)
    .bind("k", key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)
