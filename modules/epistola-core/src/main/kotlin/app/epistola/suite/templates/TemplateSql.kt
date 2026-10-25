// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt

/**
 * Joins a row aliased [alias] that names a template by identity but belongs to no catalog version
 * (a quality finding, a load-test run) to that template in the version its catalog's caller means
 * (`current_templates`), aliased `template`.
 */
fun currentTemplateJoin(alias: String): String = "JOIN current_templates template ON template.tenant_key = $alias.tenant_key AND template.resource_id = $alias.template_resource_id"

/** The address columns a caller reading through [currentTemplateJoin] still expects to select. */
const val TEMPLATE_ADDRESS_COLUMNS: String =
    "(SELECT template_catalog.catalog_key FROM catalogs template_catalog" +
        " WHERE template_catalog.tenant_key = template.tenant_key AND template_catalog.catalog_id = template.catalog_id)" +
        " AS catalog_key, (SELECT template_catalog.namespace FROM catalogs template_catalog" +
        " WHERE template_catalog.tenant_key = template.tenant_key AND template_catalog.catalog_id = template.catalog_id)" +
        " AS catalog_namespace, template.id AS template_key"

/**
 * The address columns for a query already filtered to the template at `(:catalogKey, :templateKey)`.
 *
 * The filter is what makes echoing the parameters back exact rather than an approximation, and it
 * saves joining to the row the caller has already named.
 */
const val REQUESTED_TEMPLATE_ADDRESS: String =
    "CAST(:catalogKey AS TEXT) AS catalog_key, CAST(:templateKey AS TEXT) AS template_key"
