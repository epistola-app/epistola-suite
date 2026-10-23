// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo

/**
 * The opaque `catalog_id` of the catalog a caller named by key.
 *
 * Tables reference a catalog by `catalog_id`; a caller that names it by key resolves the key here,
 * through the database function `epistola_catalog_id` (ADR 0034, phase 4: a catalog is unique by
 * namespace and key). A key is looked up in the namespace the running message names for it
 * ([CatalogNames]); otherwise it must name one catalog of the tenant, and several are refused
 * ([CatalogKeyAmbiguousException]). None is NULL, which matches no row on a read and violates the NOT
 * NULL `catalog_id` on a write -- so neither silently succeeds.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 * @param catalogParam name of the bound catalog-key parameter.
 */
fun catalogAt(tenantParam: String, catalogParam: String): String = "epistola_catalog_id(:$tenantParam, :$catalogParam)"

/**
 * The `catalog_id`s of the catalogs the keys of the bound list [listParam] name, each looked up as
 * [catalogAt] looks one up, for `catalog_id IN ${'$'}{catalogsAt(…)}`: a key two catalogs share means
 * the one the running work names, and is refused when it names none, rather than matching both.
 */
fun catalogsAt(tenantParam: String, listParam: String): String = "(SELECT epistola_catalog_id(:$tenantParam, k) FROM unnest(ARRAY[<$listParam>]::text[]) AS k)"

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
 * A predicate that the content row aliased [alias] is part of the version its catalog's callers mean
 * (see [versionAt]: the targeted version for its catalog, else the current one). For statements that reach content without a catalog key in hand: tenant-wide reads,
 * and references to a resource by its identity, possibly in another catalog (a template's theme, an
 * attribute's code list, the tenant's default theme). Usable in a `JOIN ... ON` as well as a `WHERE`.
 */
fun inCurrentVersion(alias: String): String = "$alias.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = $alias.tenant_key)"

/** The catalog [catalogKey] names in [tenantKey] (see [catalogAt]), or null. */
fun Handle.findCatalog(tenantKey: TenantKey, catalogKey: CatalogKey): Catalog? = createQuery(
    "SELECT * FROM catalogs WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")}",
)
    .bind("tenantKey", tenantKey)
    .bind("catalogKey", catalogKey)
    .mapTo<Catalog>()
    .findOne()
    .orElse(null)

/**
 * The revision of the content row of [table] at [key] (its [keyColumn]) in the version a caller means
 * of [catalogKey], or null when there is none: what a guarded write that changed nothing compares
 * against ([app.epistola.suite.common.Revisions.refuse]).
 */
fun Handle.contentRevision(table: String, keyColumn: String, tenantKey: TenantKey, catalogKey: CatalogKey, key: Any): Long? = createQuery(
    "SELECT revision FROM $table WHERE tenant_key = :t AND $keyColumn = :k AND version_id = ${versionAt("t", "c")}",
)
    .bind("t", tenantKey)
    .bind("c", catalogKey)
    .bind("k", key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)
