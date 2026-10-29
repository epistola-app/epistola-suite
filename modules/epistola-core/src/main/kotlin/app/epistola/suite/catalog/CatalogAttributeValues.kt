// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantId

/**
 * Checks the attribute values a catalog's metadata declares, by attribute key (qualified or bare) to
 * value. Catalog names no content domain (ADR 0039): the attributes domain implements this.
 */
fun interface CatalogAttributeValues {
    /** Throws a validation failure for an unknown attribute or a value it does not allow. */
    fun validate(tenantId: TenantId, values: Map<String, String>)
}
