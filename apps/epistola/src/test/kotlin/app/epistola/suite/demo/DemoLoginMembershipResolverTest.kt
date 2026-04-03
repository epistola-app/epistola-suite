package app.epistola.suite.demo

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.LoginMembershipResolver
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime

@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    app.epistola.suite.config.TestSecurityContextConfiguration::class,
)
@SpringBootTest(
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
        externalId = "system",
        email = "system@test",
        displayName = "System",
        tenantMemberships = emptyMap(),
        globalRoles = TenantRole.entries.toSet(),
        platformRoles = PlatformRole.entries.toSet(),
        currentTenantId = null,
    )

    private fun dummyUser(email: String) = User(
        id = UserKey.of("00000000-0000-0000-0000-000000000042"),
        externalId = "demo-user",
        email = email,
        displayName = "Demo User",
        provider = AuthProvider.KEYCLOAK,
        enabled = true,
        createdAt = OffsetDateTime.now(),
        lastLoginAt = null,
        tenantMemberships = emptyMap(),
    )

    @AfterEach
    fun cleanup() {
        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(systemPrincipal) {
                createdTenants.forEach { key ->
                    try {
                        mediator.send(DeleteTenant(key))
                    } catch (_: Exception) {
                        // ignore if already deleted
                    }
                }
            }
        }
        createdTenants.clear()
    }

    @Test
    fun `resolves tenant from email domain`() {
        val result = MediatorContext.runWithMediator(mediator) {
            resolver.resolve("user@acme-corp.io", dummyUser("user@acme-corp.io"))
        }

        assertThat(result).isNotNull()
        val tenantKey = TenantKey.of("acme-corp-io")
        createdTenants.add(tenantKey)
        assertThat(result!!.tenantMemberships).containsKey(tenantKey)
        assertThat(result.tenantMemberships[tenantKey]).containsExactlyInAnyOrderElementsOf(TenantRole.entries)
    }

    @Test
    fun `does not grant platform roles`() {
        val result = MediatorContext.runWithMediator(mediator) {
            resolver.resolve("user@noplat.io", dummyUser("user@noplat.io"))
        }

        assertThat(result).isNotNull()
        createdTenants.add(TenantKey.of("noplat-io"))
        assertThat(result!!.platformRoles).isEmpty()
    }

    @Test
    fun `auto-creates tenant if it does not exist`() {
        MediatorContext.runWithMediator(mediator) {
            resolver.resolve("user@newcorp.io", dummyUser("user@newcorp.io"))
        }

        val tenantKey = TenantKey.of("newcorp-io")
        createdTenants.add(tenantKey)

        val tenant = MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(systemPrincipal) {
                mediator.query(GetTenant(tenantKey))
            }
        }
        assertThat(tenant).isNotNull()
        assertThat(tenant!!.name).isEqualTo("newcorp-io")
    }

    @Test
    fun `reuses existing tenant`() {
        val tenantKey = TenantKey.of("existing-co")
        createdTenants.add(tenantKey)

        // Create tenant first
        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(systemPrincipal) {
                mediator.send(app.epistola.suite.tenants.commands.CreateTenant(id = tenantKey, name = "Existing Co"))
            }
        }

        // Resolve should succeed without error
        val result = MediatorContext.runWithMediator(mediator) {
            resolver.resolve("user@existing.co", dummyUser("user@existing.co"))
        }

        assertThat(result).isNotNull()
        assertThat(result!!.tenantMemberships).containsKey(tenantKey)
    }

    @Test
    fun `returns null for invalid email domain`() {
        val result = MediatorContext.runWithMediator(mediator) {
            resolver.resolve("invalid-email", dummyUser("invalid-email"))
        }

        assertThat(result).isNull()
    }

    @Test
    fun `converts dots to hyphens in domain`() {
        val result = MediatorContext.runWithMediator(mediator) {
            resolver.resolve("user@my.company.co.uk", dummyUser("user@my.company.co.uk"))
        }

        assertThat(result).isNotNull()
        val tenantKey = TenantKey.of("my-company-co-uk")
        createdTenants.add(tenantKey)
        assertThat(result!!.tenantMemberships).containsKey(tenantKey)
    }
}
