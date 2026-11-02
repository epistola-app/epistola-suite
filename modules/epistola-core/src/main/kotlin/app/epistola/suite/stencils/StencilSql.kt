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
 * @param versionParam name of the bound version ([app.epistola.suite.catalog.bindVersion]) the stencil is read in.
 */
internal fun stencilAtAddress(tenantParam: String, versionParam: String, keyParam: String): String = "(SELECT resource_id FROM version_stencils WHERE tenant_key = :$tenantParam AND version_id = :$versionParam AND id = :$keyParam)"
