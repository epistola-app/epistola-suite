// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.FindCatalogsByKey
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_NAMESPACE
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * [key] of a fixture tenant, by its address (ADR 0034, section 9): in the namespace [testNamespace]
 * gives. A test about two catalogs with one key names each [CatalogId] itself.
 */
fun testCatalog(tenantKey: TenantKey, key: CatalogKey): CatalogId = CatalogId(testNamespace(tenantKey, key.value), key, TenantId(tenantKey))

/**
 * The namespace a fixture tenant's catalog [catalogKey] is named in: the one catalog of the tenant
 * with that key when it has one (a catalog received from another tenant keeps that tenant's
 * namespace), else the bundled system catalog's ([SYSTEM_CATALOG_NAMESPACE]) for its key, else the
 * namespace the tenant is granted ([TestNamespaces.of]), where a catalog it creates without naming
 * one is named.
 */
fun testNamespace(tenantKey: TenantKey, catalogKey: String): String {
    val existing = TestCatalogLookup.namespaceOf(tenantKey, catalogKey)
        ?: runCatching { FindCatalogsByKey(tenantKey, CatalogKey.of(catalogKey)).query().only?.namespace }.getOrNull()
    return existing ?: if (catalogKey in SUPPLIED_BY_EPISTOLA) SYSTEM_CATALOG_NAMESPACE else TestNamespaces.of(tenantKey).namespace
}

/** The keys of the catalogs the suite bundles, which their manifests name in Epistola's namespace. */
private val SUPPLIED_BY_EPISTOLA = setOf(SYSTEM_CATALOG_KEY.value, "epistola-demo")

/** Resource [key] of [type] in fixture tenant [tenantKey]'s catalog [catalogKey], by its address ([testNamespace]). */
fun testAddress(tenantKey: TenantKey, type: CatalogResourceType, catalogKey: String, key: String): ResourceAddress = ResourceAddress(type, testNamespace(tenantKey, catalogKey), catalogKey, key)

/** [testCatalog] for a [TenantId]. */
fun testCatalog(tenantId: TenantId, key: CatalogKey): CatalogId = testCatalog(tenantId.key, key)

/** The default catalog every fixture tenant is provisioned with ([CatalogKey.DEFAULT]), in its namespace. */
fun CatalogId.Companion.default(tenantId: TenantId): CatalogId = testCatalog(tenantId, CatalogKey.DEFAULT)

/** [testCatalog] for a key given as text. */
fun testCatalog(tenantKey: TenantKey, key: String): CatalogId = testCatalog(tenantKey, CatalogKey.of(key))

/** [testCatalog] for a [TenantId] and a key given as text. */
fun testCatalog(tenantId: TenantId, key: String): CatalogId = testCatalog(tenantId.key, CatalogKey.of(key))

/** [testCatalog] for a tenant and key given as text, as raw SQL in a test holds them. */
fun testCatalog(tenantKey: String, key: String): CatalogId = testCatalog(TenantKey.of(tenantKey), CatalogKey.of(key))

/** [testCatalog] for a tenant given as text. */
fun testCatalog(tenantKey: String, key: CatalogKey): CatalogId = testCatalog(TenantKey.of(tenantKey), key)

/**
 * Where [testNamespace] reads a fixture tenant's catalogs from, bound by [IntegrationTestBase] for each
 * test, so a test may name a catalog outside a mediator call. A read, never a write.
 *
 * Bound per test thread (and the threads it starts): test classes of different Spring contexts run
 * concurrently, each against its own database, so one shared binding would read another's.
 */
object TestCatalogLookup {
    private val bound = InheritableThreadLocal<org.jdbi.v3.core.Jdbi?>()

    var jdbi: org.jdbi.v3.core.Jdbi?
        get() = bound.get()
        set(value) = bound.set(value)

    /** The namespace of [tenantKey]'s one catalog with key [catalogKey]; null for none or several. */
    fun namespaceOf(tenantKey: TenantKey, catalogKey: String): String? = jdbi?.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_key = :k")
            .bind("t", tenantKey.value)
            .bind("k", catalogKey)
            .mapTo(String::class.java)
            .list()
            .singleOrNull()
    }
}
