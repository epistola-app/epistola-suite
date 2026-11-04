// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.namespaces.NamespaceName
import app.epistola.suite.catalog.namespaces.SeedTenantNamespace
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.tenants.commands.CreateTenant

/**
 * The namespace a fixture tenant is granted when it is created (ADR 0034, section 3): its key, padded
 * to a namespace's length, under the default registry. The name every tenant's catalogs had before
 * namespaces were granted, and the one the migration grants existing tenants, so a test that names
 * no namespace gets the catalog names it always did. A test about grants creates its tenant with
 * [CreateTenant] itself.
 */
object TestNamespaces {
    const val DEFAULT_AUTHORITY: String = "exchange.epistola.app"

    fun of(tenantKey: TenantKey): NamespaceName = NamespaceName(DEFAULT_AUTHORITY, tenantKey.value.padEnd(CatalogNamespaces.MIN_LENGTH, '0'))

    /**
     * [SeedTenantNamespace] of [namespace] to [tenantKey], under the default authority unless named: it
     * stands in for the Exchange connection, or a domain, granting it. A test about how a person adds
     * one dispatches [app.epistola.suite.catalog.namespaces.GrantTenantNamespace] itself.
     */
    fun grant(tenantKey: TenantKey, namespace: String, authority: String = DEFAULT_AUTHORITY): SeedTenantNamespace = SeedTenantNamespace(tenantKey, authority, namespace)

    /** [SeedTenantNamespace] of [from]'s fixture namespace to [to]: the two share it, as departments, or test and production, do. */
    fun share(from: TenantKey, to: TenantKey): SeedTenantNamespace = of(from).let { SeedTenantNamespace(to, it.authority, it.namespace) }

    /** [CreateTenant] for a fixture tenant: granted [of] its key. */
    fun createTenant(tenantKey: TenantKey, name: String): CreateTenant = CreateTenant(id = tenantKey, name = name, namespaces = listOf(of(tenantKey)))
}
