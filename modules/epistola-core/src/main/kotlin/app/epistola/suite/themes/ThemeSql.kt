// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

/**
 * The identity of the theme a caller named by address, for writing a binding.
 *
 * An address that resolves to nothing yields a sentinel rather than NULL, so the foreign key
 * rejects the write. Resolving to NULL would instead store "no theme", silently falling the
 * template back to the tenant default — a binding quietly discarded rather than refused.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 *   Expects the catalog and key parameters named alongside it.
 */
internal fun themeAtAddress(tenantParam: String, catalogParam: String, keyParam: String): String =
    """
    CASE WHEN CAST(:$keyParam AS TEXT) IS NULL THEN NULL
         ELSE COALESCE(
             (SELECT resource_id FROM themes
               WHERE tenant_key = :$tenantParam AND catalog_key = :$catalogParam AND id = :$keyParam),
             '00000000-0000-0000-0000-000000000000'::uuid)
    END
    """

/**
 * Tenant columns plus the default theme's address, which the model still speaks even though the
 * table now stores the theme's identity. Aliased `t` and joined to `themes th`.
 */
internal const val TENANT_COLUMNS_WITH_THEME: String =
    "t.*, th.catalog_key AS default_theme_catalog_key, th.id AS default_theme_key"

internal const val TENANT_THEME_JOIN: String =
    "LEFT JOIN themes th ON th.tenant_key = t.id AND th.resource_id = t.default_theme_resource_id"

/** `RETURNING *` cannot reach the join, so the updated row is read back through it. */
internal fun updateTenantReturningTenant(set: String, where: String): String =
    """
    WITH updated AS (
        UPDATE tenants SET $set WHERE $where RETURNING *
    )
    SELECT $TENANT_COLUMNS_WITH_THEME FROM updated t $TENANT_THEME_JOIN
    """
