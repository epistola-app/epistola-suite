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
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantAccessDeniedException
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
 * Namespaces granted to tenants (ADR 0034, section 3, and its amendment of 2026-11-03): the tenant's
 * administrator adds and removes a domain's, a tenant may hold several and several tenants one, and
 * creating a tenant grants the ones it is created with.
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

            val taken = refusal { GrantTenantNamespace(tenant, "hr.gemeente-x.nl", "acme").execute() }
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
            assertThat(refused.message).isEqualTo("acme is not removed: the catalogs default, letters are named in it. Move or delete them first.")
            assertThat(ListNamespaceGrants(tenant).query().map { it.name }).containsExactly(acme)

            DeleteCatalog(testCatalog(tenant, CatalogKey.of("letters"))).execute()
            DeleteCatalog(testCatalog(tenant, CatalogKey.DEFAULT)).execute()
            assertThat(RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute()).isEqualTo(NamespaceRevocation(acme, revoked = true))
            assertThat(ListNamespaceGrants(tenant).query()).isEmpty()
            assertThat(RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute().revoked).`as`("revoking what is not held does nothing").isFalse()
        }
    }

    @Test
    fun `the tenant's administrator lists, adds and removes, and neither a platform administrator nor a content author does`() {
        val tenant = bareTenant("Tenant administrator", NamespaceName("gemeente-x.nl", "acme"))
        fun principal(id: String, roles: Set<TenantRole>, platform: Set<PlatformRole> = emptySet()) = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-00000000$id"),
            externalId = "namespace-$id",
            email = "namespace-$id@example.com",
            displayName = "Namespace $id",
            tenantMemberships = if (roles.isEmpty()) emptyMap() else mapOf(tenant to roles),
            platformRoles = platform,
            currentTenantId = tenant.takeIf { roles.isNotEmpty() },
        )

        runAs(principal("ca01", setOf(TenantRole.TENANT_ADMINISTRATOR))) {
            assertThat(GrantTenantNamespace(tenant, "gemeente-x.nl", "other").execute().grantedThrough).isEqualTo(NamespaceGrantSource.ADMINISTRATOR)
            assertThat(GetTenantAuthorities(tenant).query().domains.single().namespaces.map { it.namespace }).containsExactly("acme", "other")
            assertThat(RevokeTenantNamespace(tenant, "gemeente-x.nl", "other").execute().revoked).isTrue()
        }
        runAs(principal("ca02", TenantRole.entries.toSet() - TenantRole.TENANT_ADMINISTRATOR)) {
            assertThatThrownBy { ListNamespaceGrants(tenant).query() }.isInstanceOf(PermissionDeniedException::class.java)
            assertThatThrownBy { GrantTenantNamespace(tenant, "gemeente-x.nl", "other").execute() }.isInstanceOf(PermissionDeniedException::class.java)
            assertThatThrownBy { RevokeTenantNamespace(tenant, "gemeente-x.nl", "acme").execute() }.isInstanceOf(PermissionDeniedException::class.java)
            // What the tenant may name catalogs in is every member's to see.
            assertThat(ListHeldNamespaces(tenant).query().held).containsExactly(NamespaceName("gemeente-x.nl", "acme"))
        }
        // The platform no longer grants: a platform administrator outside the tenant changes nothing in it.
        runAs(principal("ca03", emptySet(), PlatformRole.entries.toSet())) {
            assertThatThrownBy { GrantTenantNamespace(tenant, "gemeente-x.nl", "other").execute() }.isInstanceOf(TenantAccessDeniedException::class.java)
        }
    }

    @Test
    fun `the authorities list the Exchange's namespaces apart from each domain's, domains by name`() {
        val tenant = bareTenant(
            "Authorities",
            NamespaceName("exchange.epistola.app", "acme-public"),
            NamespaceName("gemeente-x.nl", "acme"),
            NamespaceName("hr.gemeente-x.nl", "acme-hr"),
            NamespaceName("gemeente-x.nl", "acme-finance"),
        )

        withMediator {
            val authorities = GetTenantAuthorities(tenant).query()
            assertThat(authorities.exchangeAuthority).isEqualTo("exchange.epistola.app")
            assertThat(authorities.exchangeNamespaces.map { it.namespace }).containsExactly("acme-public")
            assertThat(authorities.domains.map { it.authority to it.namespaces.map { grant -> grant.namespace } }).containsExactly(
                "gemeente-x.nl" to listOf("acme", "acme-finance"),
                "hr.gemeente-x.nl" to listOf("acme-hr"),
            )
            assertThat(authorities.domainNames).containsExactly("gemeente-x.nl", "hr.gemeente-x.nl")
        }
    }
}
