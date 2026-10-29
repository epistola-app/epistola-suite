// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.catalog.CatalogAttributeValues
import app.epistola.suite.common.ids.TenantId
import org.springframework.stereotype.Component

/** A catalog's attribute values are checked as a variant's are ([validateAttributes]). */
@Component
class AttributeValuesForCatalogs : CatalogAttributeValues {
    override fun validate(tenantId: TenantId, values: Map<String, String>) = validateAttributes(tenantId, values)
}
