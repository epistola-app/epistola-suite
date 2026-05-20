package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.User
import app.epistola.suite.users.commands.CreateUser
import app.epistola.suite.users.commands.SyncTenantMemberships
import app.epistola.suite.users.commands.UpdateLastLogin
import app.epistola.suite.users.queries.GetUserByExternalId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import java.time.OffsetDateTime

@Tag("unit")
class OAuth2UserProvisioningServiceTest {

    private fun oauth2User(attributes: Map<String, Any>): OAuth2User {
        val attrs = mapOf("sub" to "test-subject", "email" to "user@example.com", "name" to "Test User") + attributes
        return DefaultOAuth2User(emptyList(), attrs, "sub")
    }

    private fun existingUser() = User(
        id = UserKey.generate(),
        externalId = "test-subject",
        email = "user@example.com",
        displayName = "Test User",
        provider = AuthProvider.KEYCLOAK,
        tenantMemberships = emptyMap(),
        enabled = true,
        createdAt = OffsetDateTime.now(),
        lastLoginAt = null,
    )

    private fun service(
        user: User,
        authProperties: AuthProperties = AuthProperties(),
        membershipResolver: LoginMembershipResolver? = null,
        syncCalls: MutableList<Map<TenantKey, Set<TenantRole>>> = mutableListOf(),
    ): OAuth2UserProvisioningService {
        val mediator = object : Mediator {
            @Suppress("UNCHECKED_CAST")
            override fun <R> send(command: Command<R>): R = when (command) {
                is UpdateLastLogin -> Unit as R
                is SyncTenantMemberships -> {
                    syncCalls.add(command.memberships)
                    Unit as R
                }
                is CreateUser -> user as R
                else -> error("Unexpected command: $command")
            }

            @Suppress("UNCHECKED_CAST")
            override fun <R> query(query: Query<R>): R = when (query) {
                is GetUserByExternalId -> user as R
                else -> error("Unexpected query: $query")
            }
        }
        return OAuth2UserProvisioningService(mediator, authProperties, membershipResolver)
    }

    @Test
    fun `parses memberships from groups attribute alone`() {
        val principal = service(existingUser()).provision(
            oauth2User(mapOf("groups" to listOf("/epistola/tenants/acme-corp/reader"))),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
        assertThat(principal.globalRoles).isEmpty()
        assertThat(principal.platformRoles).isEmpty()
    }

    @Test
    fun `parses memberships from flat roles attribute alone`() {
        val principal = service(existingUser()).provision(
            oauth2User(
                mapOf(
                    "roles" to listOf(
                        "ept_acme-corp_reader",
                        "ept_acme-corp_editor",
                        "epg_generator",
                        "eps_tenant_manager",
                    ),
                ),
            ),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.GENERATOR)
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `merges groups and flat-roles attributes into a single principal`() {
        val principal = service(existingUser()).provision(
            oauth2User(
                mapOf(
                    "groups" to listOf("/epistola/tenants/acme-corp/reader"),
                    "roles" to listOf("ept_acme-corp_editor", "epg_generator"),
                ),
            ),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.GENERATOR)
    }

    @Test
    fun `honours a custom flat-roles claim name`() {
        val customProperties = AuthProperties(flatRoles = FlatRolesProperties(claimName = "app_roles"))

        val principal = service(existingUser(), authProperties = customProperties).provision(
            oauth2User(mapOf("app_roles" to listOf("ept_acme-corp_manager"))),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")]).containsExactly(TenantRole.MANAGER)
    }

    @Test
    fun `ignores flat roles when configured claim name does not match`() {
        // Default config reads "roles" — strings in "app_roles" should be ignored.
        val principal = service(existingUser()).provision(
            oauth2User(mapOf("app_roles" to listOf("ept_acme-corp_reader"))),
            "keycloak",
        )

        assertThat(principal.tenantMemberships).isEmpty()
    }

    @Test
    fun `invokes LoginMembershipResolver only when both claim sources are empty`() {
        val resolverCalls = mutableListOf<String>()
        val resolver = object : LoginMembershipResolver {
            override fun resolve(email: String, user: User): ResolvedMemberships {
                resolverCalls.add(email)
                return ResolvedMemberships(
                    tenantMemberships = mapOf(TenantKey.of("resolved-tenant") to setOf(TenantRole.EDITOR)),
                    globalRoles = setOf(TenantRole.READER),
                )
            }
        }

        val principal = service(existingUser(), membershipResolver = resolver).provision(
            oauth2User(emptyMap()),
            "keycloak",
        )

        assertThat(resolverCalls).containsExactly("user@example.com")
        assertThat(principal.tenantMemberships[TenantKey.of("resolved-tenant")])
            .containsExactly(TenantRole.EDITOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.READER)
    }

    @Test
    fun `does not invoke resolver when flat roles produce memberships`() {
        val resolverCalls = mutableListOf<String>()
        val resolver = object : LoginMembershipResolver {
            override fun resolve(email: String, user: User): ResolvedMemberships {
                resolverCalls.add(email)
                return ResolvedMemberships(emptyMap())
            }
        }

        service(existingUser(), membershipResolver = resolver).provision(
            oauth2User(mapOf("roles" to listOf("ept_acme-corp_reader"))),
            "keycloak",
        )

        assertThat(resolverCalls).isEmpty()
    }

    @Test
    fun `syncs token memberships when any are present`() {
        val syncCalls = mutableListOf<Map<TenantKey, Set<TenantRole>>>()

        service(existingUser(), syncCalls = syncCalls).provision(
            oauth2User(mapOf("roles" to listOf("ept_acme-corp_reader", "ept_beta-org_editor"))),
            "keycloak",
        )

        assertThat(syncCalls).hasSize(1)
        assertThat(syncCalls.single()[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
        assertThat(syncCalls.single()[TenantKey.of("beta-org")]).containsExactly(TenantRole.EDITOR)
    }
}
