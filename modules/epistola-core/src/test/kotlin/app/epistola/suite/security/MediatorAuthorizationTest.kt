package app.epistola.suite.security

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Integration test verifying that [SpringMediator] correctly enforces
 * authorization declared via [Authorized] marker interfaces.
 */
class MediatorAuthorizationTest : CoreIntegrationTestBase() {

    @Test
    fun `RequiresPermission command succeeds with correct permission`() = withAuthentication {
        // testUser has all roles (including MANAGER which grants TENANT_SETTINGS)
        val tenant = CreateTenant(id = TenantKey.of("authz-perm-ok"), name = "Auth Test").let { mediator.send(it) }
        assertThat(tenant.name).isEqualTo("Auth Test")
    }

    @Test
    fun `RequiresPermission command fails without tenant access`() {
        val restrictedUser = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-000000000001"),
            externalId = "restricted-user",
            email = "restricted@example.com",
            displayName = "Restricted User",
            tenantMemberships = mapOf(TenantKey.of("other-tenant") to setOf(TenantRole.MANAGER)),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = null,
        )

        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(restrictedUser) {
                // Create tenant first (platform role check passes)
                mediator.send(CreateTenant(id = TenantKey.of("authz-no-access"), name = "No Access"))

                // Try to set default theme — user has no access to this tenant
                assertThatThrownBy {
                    mediator.send(
                        app.epistola.suite.tenants.commands.SetTenantDefaultTheme(
                            tenantId = TenantKey.of("authz-no-access"),
                            themeId = app.epistola.suite.common.ids.ThemeKey.of("nonexistent"),
                        ),
                    )
                }.isInstanceOf(TenantAccessDeniedException::class.java)
            }
        }
    }

    @Test
    fun `RequiresPermission command fails without required permission`() {
        val readerOnlyUser = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-000000000002"),
            externalId = "reader-user",
            email = "reader@example.com",
            displayName = "Reader User",
            tenantMemberships = emptyMap(),
            globalRoles = setOf(TenantRole.READER), // Only READER role — no TEMPLATE_EDIT
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = null,
        )

        // First create the tenant with our full-access test user
        val tenant = withAuthentication {
            mediator.send(CreateTenant(id = TenantKey.of("authz-no-perm"), name = "No Perm"))
        }

        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(readerOnlyUser) {
                assertThatThrownBy {
                    mediator.send(
                        app.epistola.suite.themes.commands.CreateTheme(
                            id = app.epistola.suite.common.ids.ThemeId(
                                app.epistola.suite.common.ids.ThemeKey.of("test-theme"),
                                app.epistola.suite.common.ids.TenantId(tenant.id),
                            ),
                            name = "Unauthorized Theme",
                        ),
                    )
                }.isInstanceOf(PermissionDeniedException::class.java)
            }
        }
    }

    @Test
    fun `RequiresPlatformRole fails without tenant manager role`() {
        val nonManagerUser = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-000000000003"),
            externalId = "non-manager",
            email = "nonmanager@example.com",
            displayName = "Non Manager",
            tenantMemberships = emptyMap(),
            globalRoles = setOf(TenantRole.MANAGER),
            platformRoles = emptySet(), // No platform roles
            currentTenantId = null,
        )

        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(nonManagerUser) {
                assertThatThrownBy {
                    mediator.send(CreateTenant(id = TenantKey.of("authz-no-platform"), name = "No Platform"))
                }.isInstanceOf(PlatformAccessDeniedException::class.java)
            }
        }
    }

    @Test
    fun `RequiresAuthentication succeeds for any authenticated user`() {
        val minimalUser = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-000000000004"),
            externalId = "minimal-user",
            email = "minimal@example.com",
            displayName = "Minimal User",
            tenantMemberships = emptyMap(),
            currentTenantId = null,
        )

        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(minimalUser) {
                val tenants = mediator.query(ListTenants())
                assertThat(tenants).isNotNull()
            }
        }
    }

    @Test
    fun `SystemInternal succeeds without security context`() {
        // SystemInternal commands should work even without a bound principal
        MediatorContext.runWithMediator(mediator) {
            // No SecurityContext.runWithPrincipal — principal is NOT bound
            val user = mediator.query(
                app.epistola.suite.users.queries.GetUserByExternalId(
                    externalId = "nonexistent",
                    provider = app.epistola.suite.users.AuthProvider.KEYCLOAK,
                ),
            )
            assertThat(user).isNull()
        }
    }

    @Test
    fun `non-Authorized command is rejected`() {
        // Create an anonymous command that does NOT implement Authorized
        data class RogueCommand(val data: String) : Command<String>

        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(testUser) {
                assertThatThrownBy {
                    mediator.send(RogueCommand("test"))
                }.isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("must implement Authorized")
            }
        }
    }
}
