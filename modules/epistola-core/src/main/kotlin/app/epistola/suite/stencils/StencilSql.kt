// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt

/**
 * The identity of the stencil a caller named by address.
 *
 * Versions name the stencil rather than a copy of its address, so a query that still takes an
 * address resolves it here. An address naming nothing yields NULL, which matches no version on a
 * read and violates the NOT NULL parent on a write -- so neither silently succeeds.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 */
internal fun stencilAtAddress(tenantParam: String, catalogParam: String, keyParam: String): String = "(SELECT resource_id FROM current_stencils WHERE tenant_key = :$tenantParam AND catalog_id = ${catalogAt(tenantParam, catalogParam)} AND id = :$keyParam)"
