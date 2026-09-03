// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.LoginMembershipResolver
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.TestPrincipalUsers
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import app.epistola.suite.users.commands.CreateUser
import app.epistola.suite.users.queries.GetUserByExternalId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Covers what needs a database: the tenant the resolver builds, what it is seeded with, and the
 * membership it writes. The slug rules themselves are pinned down without Spring in
 * [DemoTenantKeyDerivationTest].
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    app.epistola.suite.config.TestSecurityContextConfiguration::class,
)
@SpringBootTest(
    classes = [app.epistola.suite.EpistolaSuiteApplication::class],
    properties = [
        "epistola.demo.enabled=true",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
class DemoLoginMembershipResolverTest {

    @Autowired
    private lateinit var resolver: LoginMembershipResolver

    @Autowired
    private lateinit var mediator: Mediator

    private val createdTenants = mutableListOf<TenantKey>()

    private val systemPrincipal = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000099"),
        externalId = "demo-resolver-test-system",
        email = "system@test",
        displayName = "System",
        tenantMemberships = emptyMap(),
        globalRoles = TenantRole.entries.toSet(),
        platformRoles = PlatformRole.entries.toSet(),
        currentTenantId = null,
    )

    /** A real `users` row, so the membership the resolver writes has an FK to satisfy. */
    private fun user(email: String): User = asSystem {
        val externalId = "demo-user-${UUID.randomUUID()}"
        mediator.send(
            CreateUser(
                externalId = externalId,
                email = email,
                displayName = "Demo User",
                provider = AuthProvider.KEYCLOAK,
            ),
        )
    }

    private fun <T> asSystem(block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(systemPrincipal, block)
    }

    private fun resolve(email: String) = MediatorContext.runWithMediator(mediator) {
        resolver.resolve(email, user(email))
    }

    /** The key this address maps to — pinned exhaustively in [DemoTenantKeyDerivationTest]. */
    private fun keyFor(email: String): TenantKey = DemoLoginMembershipResolver.deriveTenantKeyFromEmail(email) ?: error("no key for $email")

    private fun track(vararg emails: String) = emails.forEach { createdTenants.add(keyFor(it)) }

    @BeforeEach
    fun ensureSystemPrincipalUser() {
        TestPrincipalUsers.ensure(mediator, systemPrincipal)
    }

    @AfterEach
    fun cleanup() {
        asSystem {
            createdTenants.forEach { key ->
                try {
                    mediator.send(DeleteTenant(key))
                } catch (_: Exception) {
                    // ignore if already deleted
                }
            }
        }
        createdTenants.clear()
    }

    @Test
    fun `gives each person their own tenant, not one per company`() {
        val alice = resolve("alice@acme-corp.io")
        val bob = resolve("bob@acme-corp.io")
        track("alice@acme-corp.io", "bob@acme-corp.io")

        assertThat(alice!!.tenantMemberships.keys).containsExactly(keyFor("alice@acme-corp.io"))
        assertThat(bob!!.tenantMemberships.keys).containsExactly(keyFor("bob@acme-corp.io"))
    }

    @Test
    fun `grants every tenant role on that tenant`() {
        val result = resolve("roles@acme.io")
        val tenantKey = keyFor("roles@acme.io")
        track("roles@acme.io")

        assertThat(result!!.tenantMemberships[tenantKey]).containsExactlyInAnyOrderElementsOf(TenantRole.entries)
    }

    @Test
    fun `grants no platform or global roles, so the user cannot reach or create other tenants`() {
        val result = resolve("scoped@acme.io")
        track("scoped@acme.io")

        // Global roles would grant access to every tenant (EpistolaPrincipal.hasAccessToTenant) and
        // would make ListTenants return the whole installation; TENANT_MANAGER would let them create
        // more tenants. A demo sandbox is neither.
        assertThat(result!!.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `the resulting principal sees only its own tenant`() {
        val otherTenant = TenantKey.of("someone-elses-tenant")
        asSystem { mediator.send(CreateTenant(id = otherTenant, name = "Someone Else")) }
        val tenantKey = keyFor("isolated@acme.io")
        createdTenants.add(otherTenant)
        track("isolated@acme.io")

        val result = resolve("isolated@acme.io")!!
        val principal = systemPrincipal.copy(
            tenantMemberships = result.tenantMemberships,
            globalRoles = result.globalRoles,
            platformRoles = result.platformRoles,
        )

        val visible = MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(principal) { mediator.query(ListTenants()) }
        }
        assertThat(visible.map { it.id }).containsExactly(tenantKey)
    }

    @Test
    fun `names the tenant after the email that created it`() {
        resolve("named@newcorp.io")
        val tenantKey = keyFor("named@newcorp.io")
        track("named@newcorp.io")

        val tenant = asSystem { mediator.query(GetTenant(tenantKey)) }
        assertThat(tenant).isNotNull()
        assertThat(tenant!!.name).isEqualTo("named@newcorp.io")
    }

    @Test
    fun `seeds the new tenant with the demo catalog and both environments`() {
        resolve("seeded@newcorp.io")
        val tenantKey = keyFor("seeded@newcorp.io")
        track("seeded@newcorp.io")

        asSystem {
            assertThat(mediator.query(GetCatalog(tenantKey, CatalogKey.of("epistola-demo")))).isNotNull()
            val environments = mediator.query(ListEnvironments(TenantId(tenantKey)))
            assertThat(environments.map { it.id.value }).containsExactlyInAnyOrder("staging", "production")
        }
    }

    @Test
    fun `persists the membership so the tenant survives a differently-routed login`() {
        val email = "persisted@acme.io"
        val tenantKey = keyFor(email)
        track(email)

        val user = MediatorContext.runWithMediator(mediator) {
            val u = user(email)
            resolver.resolve(email, u)
            u
        }

        val reloaded = asSystem { mediator.query(GetUserByExternalId(user.externalId, AuthProvider.KEYCLOAK)) }
        assertThat(reloaded!!.tenantMemberships[tenantKey]).containsExactlyInAnyOrderElementsOf(TenantRole.entries)
    }

    @Test
    fun `reuses the tenant on a later login instead of rebuilding it`() {
        val email = "returning@acme.io"
        val tenantKey = keyFor(email)
        track(email)

        val first = resolve(email)
        val second = resolve(email)

        assertThat(first!!.tenantMemberships.keys).containsExactly(tenantKey)
        assertThat(second!!.tenantMemberships.keys).containsExactly(tenantKey)
    }

    @Test
    fun `keeps apart two addresses whose local parts slugify alike`() {
        // Both reduce to the label "j-doe-test"; the hash is what stops them sharing a sandbox.
        val first = resolve("j.doe+test@acme.io")
        val second = resolve("j.doe.test@acme.io")
        track("j.doe+test@acme.io", "j.doe.test@acme.io")

        assertThat(first!!.tenantMemberships.keys.single().value).startsWith("j-doe-test-")
        assertThat(second!!.tenantMemberships.keys.single().value).startsWith("j-doe-test-")
        assertThat(first.tenantMemberships.keys).isNotEqualTo(second.tenantMemberships.keys)
    }

    @Test
    fun `declines an address it cannot derive a key from`() {
        assertThat(resolve("invalid-email")).isNull()
    }
}
