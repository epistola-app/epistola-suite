// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

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
 * @param tenantParam name of the bound tenant parameter, which differs between call sites.
 */
fun templateAtAddress(tenantParam: String, catalogParam: String, keyParam: String): String = "(SELECT resource_id FROM document_templates WHERE tenant_key = :$tenantParam" +
    " AND catalog_key = :$catalogParam AND id = :$keyParam)"

/**
 * Joins a hierarchy row aliased [alias] to the template it belongs to, aliased `template`, so a
 * query can report the address it no longer stores.
 */
fun templateJoin(alias: String): String = "JOIN document_templates template ON template.tenant_key = $alias.tenant_key" +
    " AND template.resource_id = $alias.template_resource_id"

/** The address columns a caller reading through [templateJoin] still expects to select. */
const val TEMPLATE_ADDRESS_COLUMNS: String =
    "template.catalog_key AS catalog_key, template.id AS template_key"

/**
 * The address columns for a query already filtered to the template at `(:catalogKey, :templateKey)`.
 *
 * The filter is what makes echoing the parameters back exact rather than an approximation, and it
 * saves joining to the row the caller has already named.
 */
const val REQUESTED_TEMPLATE_ADDRESS: String =
    "CAST(:catalogKey AS TEXT) AS catalog_key, CAST(:templateKey AS TEXT) AS template_key"
