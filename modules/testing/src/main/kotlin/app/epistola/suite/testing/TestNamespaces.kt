// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.namespaces.GrantTenantNamespace
import app.epistola.suite.catalog.namespaces.NamespaceName
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

    /** [GrantTenantNamespace] of [namespace] to [tenantKey], as a platform administrator grants one; under the default authority unless named. */
    fun grant(tenantKey: TenantKey, namespace: String, authority: String = DEFAULT_AUTHORITY): GrantTenantNamespace = GrantTenantNamespace(tenantKey, authority, namespace)

    /** [GrantTenantNamespace] of [from]'s fixture namespace to [to]: the two share it, as departments, or test and production, do. */
    fun share(from: TenantKey, to: TenantKey): GrantTenantNamespace = of(from).let { GrantTenantNamespace(to, it.authority, it.namespace) }

    /** [CreateTenant] for a fixture tenant: granted [of] its key. */
    fun createTenant(tenantKey: TenantKey, name: String): CreateTenant = CreateTenant(id = tenantKey, name = name, namespaces = listOf(of(tenantKey)))
}
