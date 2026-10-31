// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformAccessDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.TestTenantCounter
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Namespaces granted to tenants (ADR 0034, section 3): a platform administrator grants and revokes
 * them, a tenant may hold several and several tenants one, and creating a tenant grants the ones it is
 * created with.
 */
class NamespaceGrantsTest : IntegrationTestBase() {

    private fun bareTenant(name: String, vararg namespaces: NamespaceName): TenantKey = withMediator {
        CreateTenant(TenantKey.of("nsgrant-${TestTenantCounter.next("nsgrant")}"), name, namespaces.toList()).execute().id
    }

    private fun refusal(block: () -> Unit): ValidationException {
        var caught: ValidationException? = null
        assertThatThrownBy { block() }.isInstanceOfSatisfying(ValidationException::class.java) { caught = it }
        return caught!!
    }

    @Test
    fun `a tenant created with namespaces holds them, granted by its creator, and its default catalog is named in the first`() {
        val acme = NamespaceName("gemeente-x.nl", "acme")
        val hr = NamespaceName("gemeente-x.nl", "acme-hr")
        val tenant = bareTenant("Created with namespaces", acme, hr)

        withMediator {
            val grants = ListNamespaceGrants(tenant).query()
            assertThat(grants.map { it.name }).containsExactly(acme, hr)
            assertThat(grants).allSatisfy {
                assertThat(it.grantedThrough).isEqualTo(NamespaceGrantSource.ADMINISTRATOR)
                assertThat(it.grantedBy).isEqualTo(TestPrincipalUser.ID)
                assertThat(it.grantedByName).isEqualTo(TestPrincipalUser.DISPLAY_NAME)
            }
            val default = GetCatalog(testCatalog(tenant, CatalogKey.DEFAULT)).query()!!
            assertThat(default.registry to default.namespace).isEqualTo("gemeente-x.nl" to "acme")
        }
    }

    @Test
    fun `a tenant created without a namespace holds none, and its default catalog is named under local, not after its key`() {
        val tenant = bareTenant("Created bare")

        withMediator {
            assertThat(ListNamespaceGrants(tenant).query()).isEmpty()
            val default = GetCatalog(testCatalog(tenant, CatalogKey.DEFAULT)).query()!!
            assertThat(default.registry to default.namespace).isEqualTo(LocalAuthority.NAME to LocalAuthority.DEFAULT_NAMESPACE)
        }
    }

    @Test
    fun `an administrator grants namespaces, once each, and several tenants may hold the same one`() {
        val first = bareTenant("Grants first")
        val second = bareTenant("Grants second")

        withMediator {
            val granted = GrantTenantNamespace(first, "gemeente-x.nl", "acme").execute()
            assertThat(granted.name).isEqualTo(NamespaceName("gemeente-x.nl", "acme"))
            assertThat(granted.grantedThrough).isEqualTo(NamespaceGrantSource.ADMINISTRATOR)

            GrantTenantNamespace(first, "gemeente-x.nl", "acme").execute()
            GrantTenantNamespace(first, "gemeente-x.nl", "acme-finance").execute()
            GrantTenantNamespace(second, "gemeente-x.nl", "acme").execute()

            assertThat(ListNamespaceGrants(first).query().map { it.namespace }).`as`("granting again changes nothing").containsExactly("acme", "acme-finance")
            assertThat(ListNamespaceGrants(second).query().map { it.namespace }).containsExactly("acme")
            assertThat(ListHeldNamespaces(first).query().held).containsExactly(NamespaceName("gemeente-x.nl", "acme"), NamespaceName("gemeente-x.nl", "acme-finance"))
        }
    }

    @Test
    fun `one namespace under two authorities, local, and a name that is not one are refused`() {
        val tenant = bareTenant("Grants refused")

        withMediator {
            GrantTenantNamespace(tenant, "gemeente-x.nl", "acme").execute()

            val taken = refusal { GrantTenantNamespace(tenant, "exchange.epistola.app", "acme").execute() }
            assertThat(taken.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_TAKEN)
            assertThat(taken.message).contains("under gemeente-x.nl already")

            assertThat(refusal { GrantTenantNamespace(tenant, LocalAuthority.NAME, "personnel").execute() }.message).contains("without a grant")
            assertThat(refusal { GrantTenantNamespace(tenant, "localhost", "personnel").execute() }.field).isEqualTo("authority")
            assertThat(refusal { GrantTenantNamespace(tenant, "gemeente-x.nl", "Not_A_Slug").execute() }.field).isEqualTo("namespace")
            assertThat(ListNamespaceGrants(tenant).query().map { it.name }).containsExactly(NamespaceName("gemeente-x.nl", "acme"))
        }
    }

    @Test
    fun `a namespace is revoked only once no catalog the tenant authors is named in it, and the refusal names them`() {
        val acme = NamespaceName("gemeente-x.nl", "acme")
        val tenant = bareTenant("Revoke", acme)

        withMediator {
            CreateCatalog(tenant, CatalogKey.of("letters"), "Letters", namespace = "acme").execute()

            val refused = refusal { RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute() }
            assertThat(refused.code).isEqualTo(ValidationCode.CATALOG_NAMESPACE_IN_USE)
            assertThat(refused.message).isEqualTo("acme is not revoked: the catalogs default, letters are named in it. Move or delete them first.")
            assertThat(ListNamespaceGrants(tenant).query().map { it.name }).containsExactly(acme)

            DeleteCatalog(testCatalog(tenant, CatalogKey.of("letters"))).execute()
            DeleteCatalog(testCatalog(tenant, CatalogKey.DEFAULT)).execute()
            assertThat(RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute()).isEqualTo(NamespaceRevocation(acme, revoked = true))
            assertThat(ListNamespaceGrants(tenant).query()).isEmpty()
            assertThat(RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute().revoked).`as`("revoking what is not held does nothing").isFalse()
        }
    }

    @Test
    fun `only a platform administrator lists, grants and revokes`() {
        val tenant = bareTenant("Platform only", NamespaceName("gemeente-x.nl", "acme"))
        val tenantAdministrator = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-00000000ca01"),
            externalId = "namespace-tenant-admin",
            email = "namespace-tenant-admin@example.com",
            displayName = "Tenant Administrator",
            tenantMemberships = mapOf(tenant to TenantRole.entries.toSet()),
            platformRoles = emptySet(),
            currentTenantId = tenant,
        )

        runAs(tenantAdministrator) {
            assertThatThrownBy { ListNamespaceGrants(tenant).query() }.isInstanceOf(PlatformAccessDeniedException::class.java)
            assertThatThrownBy { GrantTenantNamespace(tenant, "gemeente-x.nl", "other").execute() }.isInstanceOf(PlatformAccessDeniedException::class.java)
            assertThatThrownBy { RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute() }.isInstanceOf(PlatformAccessDeniedException::class.java)
            // What the tenant may name catalogs in is the tenant's own to see.
            assertThat(ListHeldNamespaces(tenant).query().held).containsExactly(NamespaceName("gemeente-x.nl", "acme"))
        }
    }
}
