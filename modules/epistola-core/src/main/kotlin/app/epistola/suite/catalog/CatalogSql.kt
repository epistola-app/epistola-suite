// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.SqlStatement
import java.util.UUID

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
 * Binds the resolved catalog version [versionId] as [name], for `version_id = :name` (ADR 0049).
 *
 * Every version of a catalog lives in the same tables, so a statement on content names the version it
 * means by its `version_id`, resolved from the version address a message carries
 * ([ContentVersions][app.epistola.suite.catalog.releases.ContentVersions]). Null, for a catalog with no
 * such version, matches no row on a read and violates the NOT NULL `version_id` on a write.
 */
fun <T : SqlStatement<T>> T.bindVersion(name: String, versionId: UUID?): T = bindByType(name, versionId, UUID::class.java)

/**
 * A scalar subquery for the row of release [versionParam] of a catalog (ADR 0036: every version is a
 * row of `catalog_versions`), or NULL when there is no such release. For statements that name a
 * release by its number, such as a deployment's.
 */
fun releaseAt(tenantParam: String, catalogParam: String, versionParam: String): String = "(SELECT rv.version_id FROM catalog_versions rv WHERE rv.tenant_key = :$tenantParam AND rv.catalog_id = ${catalogAt(tenantParam, catalogParam)} AND rv.state = 'released' AND rv.version = :$versionParam)"

/**
 * A predicate that the row aliased [alias] (with `tenant_key`, `catalog_id` and `version_id`) is of its
 * catalog's next version: the default version, asked for by name (ADR 0049), for a read across
 * catalogs that means every catalog's default, such as a tenant-wide list. Usable in a `JOIN ... ON`
 * as well as a `WHERE`.
 */
fun inNextVersion(alias: String): String = "$alias.version_id = (SELECT nv.next_version_id FROM catalogs nv WHERE nv.tenant_key = $alias.tenant_key AND nv.catalog_id = $alias.catalog_id)"

/**
 * A predicate that the row aliased [alias] is of version [versionParam] when that is a version of the
 * row's catalog, else of its catalog's next version ([inNextVersion]): for a read across catalogs made
 * for one version of one of them, such as a tenant-wide list a draft's page shows. Bind the version
 * with [bindVersion]; null means every catalog's next version.
 */
fun inVersionOrNext(alias: String, versionParam: String): String = "$alias.version_id = COALESCE(" +
    "(SELECT ov.version_id FROM catalog_versions ov WHERE ov.tenant_key = $alias.tenant_key AND ov.catalog_id = $alias.catalog_id AND ov.version_id = CAST(:$versionParam AS uuid)), " +
    "(SELECT nv.next_version_id FROM catalogs nv WHERE nv.tenant_key = $alias.tenant_key AND nv.catalog_id = $alias.catalog_id))"

/**
 * The columns of a [Catalog] row over `catalogs c` joined with [CATALOG_RELEASE_JOIN]: the catalog's
 * own, and its highest release as `latest_release_version`, `latest_release_fingerprint` and
 * `latest_released_at` (ADR 0036: a query over its released rows, not a pointer that could drift).
 */
const val CATALOG_ROW_COLUMNS = "c.*, cr.version AS latest_release_version, cr.fingerprint AS latest_release_fingerprint, cr.released_at AS latest_released_at"

/** Joins `catalogs c` to its highest release (`catalog_current_releases`, `cr`). */
const val CATALOG_RELEASE_JOIN = "LEFT JOIN catalog_current_releases cr ON cr.tenant_key = c.tenant_key AND cr.catalog_id = c.catalog_id"

/**
 * Whether version row [alias] is its catalog's next version (ADR 0036): `catalogs.next_version_id`
 * names it. A boolean expression, false when the catalog has no next version.
 */
fun isNext(alias: String): String = "COALESCE($alias.version_id = (SELECT nc.next_version_id FROM catalogs nc WHERE nc.tenant_key = $alias.tenant_key AND nc.catalog_id = $alias.catalog_id), FALSE)"

/** Makes version [versionParam] the next version of the catalog it belongs to (`catalogs.next_version_id`). */
fun setNext(tenantParam: String, versionParam: String): String = "UPDATE catalogs c SET next_version_id = v.version_id FROM catalog_versions v " +
    "WHERE v.tenant_key = :$tenantParam AND v.version_id = :$versionParam AND c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id"

/** A predicate that the row aliased [alias] belongs to a draft, not a release: for a write that names no catalog. */
fun inDraft(alias: String): String = "EXISTS (SELECT 1 FROM catalog_versions fv WHERE fv.tenant_key = $alias.tenant_key AND fv.version_id = $alias.version_id AND fv.state = 'draft')"

/** The catalog at [catalog] (see [catalogAt]), or null. */
fun Handle.findCatalog(catalog: CatalogId): Catalog? = createQuery(
    "SELECT $CATALOG_ROW_COLUMNS FROM catalogs c $CATALOG_RELEASE_JOIN WHERE c.tenant_key = :tenantKey AND c.catalog_id = ${catalogAt("tenantKey", "catalogKey")}",
)
    .bind("tenantKey", catalog.tenantKey)
    .bindCatalog("catalogKey", catalog)
    .mapTo<Catalog>()
    .findOne()
    .orElse(null)

/**
 * The revision of the content row of [table] at [key] (its [keyColumn]) in version [versionId] of a
 * catalog of [tenant], or null when there is none: what a guarded write that changed nothing compares
 * against ([app.epistola.suite.common.Revisions.refuse]).
 */
fun Handle.contentRevision(table: String, keyColumn: String, tenant: TenantKey, versionId: UUID?, key: Any): Long? = createQuery(
    "SELECT revision FROM $table WHERE tenant_key = :t AND $keyColumn = :k AND version_id = :v",
)
    .bind("t", tenant)
    .bindVersion("v", versionId)
    .bind("k", key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)

/**
 * Joins a variant aliased [alias] of `version_template_variants` to its template in
 * `version_templates`, aliased `template`, of the same version, so a query can report the template's
 * address. The views are the read model the catalog's migrations define, so the join belongs with them.
 */
fun templateViewJoin(alias: String): String = "JOIN version_templates template ON template.tenant_key = $alias.tenant_key AND template.version_id = $alias.version_id AND template.resource_id = $alias.template_resource_id"
