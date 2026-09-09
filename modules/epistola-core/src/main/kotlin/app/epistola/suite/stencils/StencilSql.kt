// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

/**
 * The identity of the stencil a caller named by address.
 *
 * Versions name the stencil rather than a copy of its address, so a query that still takes an
 * address resolves it here. An address naming nothing yields NULL, which matches no version on a
 * read and violates the NOT NULL parent on a write -- so neither silently succeeds.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 */
internal fun stencilAtAddress(tenantParam: String, catalogParam: String, keyParam: String): String = "(SELECT resource_id FROM stencils WHERE tenant_key = :$tenantParam" +
    " AND catalog_key = :$catalogParam AND id = :$keyParam)"

/** A version's own columns plus the parent key the model still carries. Aliased `versions`/`stencil`. */
internal const val STENCIL_VERSION_COLUMNS: String = "versions.*, stencil.id AS stencil_key"

internal const val STENCIL_VERSION_PARENT_JOIN: String =
    "FROM stencil_versions versions" +
        " JOIN stencils stencil ON stencil.tenant_key = versions.tenant_key" +
        " AND stencil.resource_id = versions.stencil_resource_id"

/**
 * Updates versions of the stencil at `(:tenantId, :catalogKey, :stencilId)` and reads the changed
 * rows back through the parent join, which `RETURNING *` cannot reach.
 */
internal fun updateStencilVersionsReturning(set: String, where: String): String =
    """
    WITH updated AS (
        UPDATE stencil_versions SET $set
        WHERE tenant_key = :tenantId
          AND stencil_resource_id = ${stencilAtAddress("tenantId", "catalogKey", "stencilId")}
          AND $where
        RETURNING *
    )
    SELECT updated.*, stencil.id AS stencil_key
    FROM updated
    JOIN stencils stencil ON stencil.tenant_key = updated.tenant_key
                         AND stencil.resource_id = updated.stencil_resource_id
    """
