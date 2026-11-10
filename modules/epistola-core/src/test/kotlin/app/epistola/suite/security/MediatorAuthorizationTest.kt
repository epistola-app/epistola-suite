// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.cluster.ForgetClusterNode
import app.epistola.suite.cluster.ListClusterNodes
import app.epistola.suite.cluster.schedules.ListClusterScheduledTaskNodeStates
import app.epistola.suite.cluster.schedules.ListClusterScheduledTasks
import app.epistola.suite.cluster.timers.ListClusterTimers
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.generation.collect.queries.ListConsumerStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Integration test verifying that [SpringMediator] correctly enforces
 * authorization declared via [Authorized] marker interfaces.
 */
class MediatorAuthorizationTest : IntegrationTestBase() {

    @Test
    fun `RequiresPermission command succeeds with correct permission`(): Unit = withAuthentication {
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
            tenantMemberships = mapOf(TenantKey.of("other-tenant") to setOf(TenantRole.TENANT_ADMINISTRATOR)),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = null,
        )
        ensureUser(restrictedUser)

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
                            catalog = testCatalog(TenantKey.of("authz-no-access"), app.epistola.suite.common.ids.CatalogKey.DEFAULT),
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
            globalRoles = setOf(TenantRole.CONTENT_VIEWER), // Only READER role — no TEMPLATE_EDIT
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = null,
        )
        ensureUser(readerOnlyUser)

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
                                app.epistola.suite.common.ids.CatalogId.default(app.epistola.suite.common.ids.TenantId(tenant.id)),
                            ),
                            name = "Unauthorized Theme",
                            catalogVersion = null,
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
            globalRoles = setOf(TenantRole.TENANT_ADMINISTRATOR),
            platformRoles = emptySet(), // No platform roles
            currentTenantId = null,
        )
        ensureUser(nonManagerUser)

        MediatorContext.runWithMediator(mediator) {
            SecurityContext.runWithPrincipal(nonManagerUser) {
                assertThatThrownBy {
                    mediator.send(CreateTenant(id = TenantKey.of("authz-no-platform"), name = "No Platform"))
                }.isInstanceOf(PlatformAccessDeniedException::class.java)
            }
        }
    }

    @Test
    fun `a platform observer reads diagnostics in a tenant it is not a member of, and nothing else`() {
        val tenantKey = withAuthentication { mediator.send(CreateTenant(id = TenantKey.of("authz-observed"), name = "Observed")).id }
        val observer = principal("00000000-0000-0000-0000-000000000005", "observer", platformRoles = setOf(PlatformRole.PLATFORM_OBSERVER))

        asPrincipal(observer) {
            assertThat(mediator.query(ListConsumerStatus(tenantKey))).isNotNull()
            assertThatThrownBy { mediator.query(ListEnvironments(app.epistola.suite.common.ids.TenantId(tenantKey))) }.isInstanceOf(TenantAccessDeniedException::class.java)
            assertThatThrownBy { mediator.query(ListDocuments(tenantKey)) }.isInstanceOf(TenantAccessDeniedException::class.java)
        }
    }

    @Test
    fun `a content viewer does not read generated documents, a document reader does`() {
        val tenantKey = withAuthentication { mediator.send(CreateTenant(id = TenantKey.of("authz-documents"), name = "Documents")).id }
        val viewer = principal("00000000-0000-0000-0000-000000000006", "viewer", memberships = mapOf(tenantKey to setOf(TenantRole.CONTENT_VIEWER)))
        val reader = principal("00000000-0000-0000-0000-000000000007", "reader", memberships = mapOf(tenantKey to setOf(TenantRole.DOCUMENT_READER)))

        asPrincipal(viewer) {
            assertThatThrownBy { mediator.query(ListDocuments(tenantKey)) }.isInstanceOf(PermissionDeniedException::class.java)
        }
        asPrincipal(reader) {
            assertThat(mediator.query(ListDocuments(tenantKey))).isEmpty()
        }
    }

    @Test
    fun `the cluster is read by the installation administrator and the platform observer, by no tenant role`() {
        val tenantAdministrator = principal("00000000-0000-0000-0000-000000000009", "cluster-tenant-admin", globalRoles = TenantRole.entries.toSet(), platformRoles = setOf(PlatformRole.TENANT_MANAGER))
        val observer = principal("00000000-0000-0000-0000-000000000010", "cluster-observer", platformRoles = setOf(PlatformRole.PLATFORM_OBSERVER))
        val administrator = principal("00000000-0000-0000-0000-000000000011", "cluster-admin", platformRoles = setOf(PlatformRole.INSTALLATION_ADMINISTRATOR))

        asPrincipal(tenantAdministrator) {
            listOf(ListClusterNodes, ListClusterTimers(), ListClusterScheduledTasks, ListClusterScheduledTaskNodeStates).forEach { read ->
                assertThatThrownBy { mediator.query(read) }
                    .`as`("$read")
                    .isInstanceOf(PlatformAccessDeniedException::class.java)
                    .extracting { (it as PlatformAccessDeniedException).requiredRole }
                    .isEqualTo(PlatformRole.INSTALLATION_ADMINISTRATOR)
            }
        }
        listOf(observer, administrator).forEach { reader ->
            asPrincipal(reader) {
                assertThat(mediator.query(ListClusterNodes)).isNotNull()
                assertThat(mediator.query(ListClusterTimers())).isNotNull()
                assertThat(mediator.query(ListClusterScheduledTasks)).isNotNull()
                assertThat(mediator.query(ListClusterScheduledTaskNodeStates)).isNotNull()
            }
        }
    }

    @Test
    fun `forgetting a cluster node needs the installation administrator, not a tenant administrator`() {
        val tenantAdministrator = principal(
            "00000000-0000-0000-0000-000000000008",
            "tenant-admin",
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        )

        asPrincipal(tenantAdministrator) {
            assertThatThrownBy { mediator.send(ForgetClusterNode(nodeId = "gone")) }
                .isInstanceOf(PlatformAccessDeniedException::class.java)
                .extracting { (it as PlatformAccessDeniedException).requiredRole }
                .isEqualTo(PlatformRole.INSTALLATION_ADMINISTRATOR)
        }
    }

    @Test
    fun `an authorized operation reads what it needs without the caller's view permission, and writes nothing more`() {
        val tenantKey = withAuthentication { mediator.send(CreateTenant(id = TenantKey.of("authz-operation"), name = "Operation")).id }
        val other = withAuthentication { mediator.send(CreateTenant(id = TenantKey.of("authz-operation-other"), name = "Other")).id }
        val author = principal(
            "00000000-0000-0000-0000-000000000009",
            "author-only",
            memberships = mapOf(tenantKey to setOf(TenantRole.CONTENT_AUTHOR), other to setOf(TenantRole.CONTENT_AUTHOR)),
        )
        val environments = ListEnvironments(app.epistola.suite.common.ids.TenantId(tenantKey))

        asPrincipal(author) {
            assertThatThrownBy { mediator.query(environments) }.`as`("outside an operation, a read needs its permission").isInstanceOf(PermissionDeniedException::class.java)
            authorizedOperation(tenantKey, Permission.TEMPLATE_EDIT) {
                assertThat(mediator.query(environments)).`as`("inside, it is part of the operation").isNotNull()
                assertThatThrownBy { mediator.query(ListEnvironments(app.epistola.suite.common.ids.TenantId(other))) }
                    .`as`("a read of another tenant is not").isInstanceOf(PermissionDeniedException::class.java)
                assertThatThrownBy { mediator.send(CreateEnvironment(EnvironmentId(EnvironmentKey.of("refused"), app.epistola.suite.common.ids.TenantId(tenantKey)), "Refused")) }
                    .`as`("a command is checked as always").isInstanceOf(PermissionDeniedException::class.java)
                partOfCurrentCommand(tenantKey) {
                    assertThatThrownBy { mediator.query(ListEnvironments(app.epistola.suite.common.ids.TenantId(other))) }
                        .`as`("outside a command's handling, part of a command grants nothing").isInstanceOf(PermissionDeniedException::class.java)
                }
            }
            assertThatThrownBy { mediator.query(environments) }.`as`("the grant ends with the operation").isInstanceOf(PermissionDeniedException::class.java)
            assertThatThrownBy { authorizedOperation(tenantKey, Permission.TENANT_SETTINGS) { } }
                .`as`("the operation's own permission is required").isInstanceOf(PermissionDeniedException::class.java)
        }
    }

    private fun principal(
        id: String,
        name: String,
        memberships: Map<TenantKey, Set<TenantRole>> = emptyMap(),
        globalRoles: Set<TenantRole> = emptySet(),
        platformRoles: Set<PlatformRole> = emptySet(),
    ) = EpistolaPrincipal(
        userId = UserKey.of(id),
        externalId = name,
        email = "$name@example.com",
        displayName = name,
        tenantMemberships = memberships,
        globalRoles = globalRoles,
        platformRoles = platformRoles,
        currentTenantId = null,
    ).also { ensureUser(it) }

    private fun asPrincipal(principal: EpistolaPrincipal, block: () -> Unit) {
        MediatorContext.runWithMediator(mediator) { SecurityContext.runWithPrincipal(principal) { block() } }
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
        ensureUser(minimalUser)

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
