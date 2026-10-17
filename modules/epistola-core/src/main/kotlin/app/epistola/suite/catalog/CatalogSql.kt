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
 * Tables reference a catalog by `catalog_id`; a caller that still names it by key resolves the key
 * here, in one place. `uq_catalogs_key_until_named` keeps a key unique in its tenant until callers
 * name catalogs fully, so this yields one catalog or none. None is NULL, which matches no row on a
 * read and violates the NOT NULL `catalog_id` on a write -- so neither silently succeeds.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 * @param catalogParam name of the bound catalog-key parameter.
 */
fun catalogAt(tenantParam: String, catalogParam: String): String = "(SELECT catalog_id FROM catalogs WHERE tenant_key = :$tenantParam AND catalog_key = :$catalogParam)"

/**
 * The `version_id` of the current version of the catalog a caller named by key: its installed release
 * when it is subscribed, else its highest open draft (the `catalog_current_versions` view).
 *
 * Content rows belong to a catalog version (contract ADR 0002), so every statement on content names
 * one (`ContentVersionSqlTest`). A caller that does not know about versions -- the UI, the REST paths
 * without a version, tenant-wide reads -- means this one. A catalog that does not exist yields NULL,
 * which matches no row on a read and violates the NOT NULL `version_id` on a write.
 *
 * @param tenantParam name of the bound tenant parameter.
 * @param catalogParam name of the bound catalog-key parameter.
 */
fun versionAt(tenantParam: String, catalogParam: String): String = "(SELECT cv.version_id FROM catalog_current_versions cv JOIN catalogs c ON c.tenant_key = cv.tenant_key AND c.catalog_id = cv.catalog_id WHERE c.tenant_key = :$tenantParam AND c.catalog_key = :$catalogParam)"

/**
 * The `version_id` of the version numbered [versionParam] of the catalog a caller named by key: one
 * of its open drafts, or its installed release. A released version has no row (its content is the
 * retained release content), so it yields NULL, as does a number the catalog does not have.
 *
 * For callers that name the version, such as the contract's `…/versions/{v}` paths (slice c); one
 * that does not uses [versionAt].
 *
 * @param tenantParam name of the bound tenant parameter.
 * @param catalogParam name of the bound catalog-key parameter.
 * @param versionParam name of the bound version-number parameter.
 */
fun versionNamed(tenantParam: String, catalogParam: String, versionParam: String): String = "(SELECT v.version_id FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id WHERE c.tenant_key = :$tenantParam AND c.catalog_key = :$catalogParam AND v.version = :$versionParam)"

/**
 * A predicate that the content row aliased [alias] is part of its catalog's current version (see
 * [versionAt]). For statements that reach content without a catalog key in hand: tenant-wide reads,
 * and references to a resource by its identity, possibly in another catalog (a template's theme, an
 * attribute's code list, the tenant's default theme). Usable in a `JOIN ... ON` as well as a `WHERE`.
 */
fun inCurrentVersion(alias: String): String = "$alias.version_id IN (SELECT cv.version_id FROM catalog_current_versions cv WHERE cv.tenant_key = $alias.tenant_key)"

/** The catalog with key [catalogKey] in [tenantKey], or null. */
fun Handle.findCatalog(tenantKey: TenantKey, catalogKey: CatalogKey): Catalog? = createQuery(
    "SELECT * FROM catalogs WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
)
    .bind("tenantKey", tenantKey)
    .bind("catalogKey", catalogKey)
    .mapTo<Catalog>()
    .findOne()
    .orElse(null)
