// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt

/**
 * The identity of the template a caller named by address.
 *
 * Everything that describes a template's current state -- variants, versions, contract versions,
 * activations, quality findings, load-test runs -- names the template rather than carrying a copy
 * of its address, so a query that still takes an address resolves it here. An address naming
 * nothing yields NULL, which matches no row on a read and violates the NOT NULL parent on a write,
 * so neither silently succeeds.
 *
 * Public rather than internal: quality and load-test rows hang off a template too, and their
 * modules address one the same way.
 *
 * The address is looked up in the catalog's current version ([versionAt]); a template keeps its
 * identity in every version, so the id is the same whichever version holds it.
 *
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 */
fun templateAtAddress(tenantParam: String, catalogParam: String, keyParam: String): String = "(SELECT resource_id FROM document_templates WHERE tenant_key = :$tenantParam AND version_id = ${versionAt(tenantParam, catalogParam)} AND id = :$keyParam)"

/**
 * Joins a hierarchy row aliased [alias] (a variant, which carries its template's `version_id`) to the
 * template it belongs to in the same catalog version, aliased `template`, so a query can report the
 * address it no longer stores.
 */
fun templateJoin(alias: String): String = "JOIN document_templates template ON template.tenant_key = $alias.tenant_key AND template.version_id = $alias.version_id AND template.resource_id = $alias.template_resource_id"

/**
 * Joins a row aliased [alias] that names a template by identity but belongs to no catalog version
 * (a quality finding, a load-test run) to that template in its catalog's current version, aliased
 * `template`.
 */
fun currentTemplateJoin(alias: String): String = "JOIN document_templates template ON template.tenant_key = $alias.tenant_key AND template.resource_id = $alias.template_resource_id AND ${inCurrentVersion("template")}"

/** The address columns a caller reading through [templateJoin] still expects to select. */
const val TEMPLATE_ADDRESS_COLUMNS: String =
    "(SELECT template_catalog.catalog_key FROM catalogs template_catalog" +
        " WHERE template_catalog.tenant_key = template.tenant_key AND template_catalog.catalog_id = template.catalog_id)" +
        " AS catalog_key, template.id AS template_key"

/**
 * The address columns for a query already filtered to the template at `(:catalogKey, :templateKey)`.
 *
 * The filter is what makes echoing the parameters back exact rather than an approximation, and it
 * saves joining to the row the caller has already named.
 */
const val REQUESTED_TEMPLATE_ADDRESS: String =
    "CAST(:catalogKey AS TEXT) AS catalog_key, CAST(:templateKey AS TEXT) AS template_key"
