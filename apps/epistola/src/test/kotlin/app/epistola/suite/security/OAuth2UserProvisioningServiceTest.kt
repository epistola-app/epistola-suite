// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
            oauth2User(mapOf("groups" to listOf("/epistola/tenants/acme-corp/content-viewer"))),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(principal.globalRoles).isEmpty()
        assertThat(principal.platformRoles).isEmpty()
    }

    @Test
    fun `parses memberships from flat roles attribute alone`() {
        val principal = service(existingUser()).provision(
            oauth2User(
                mapOf(
                    "roles" to listOf(
                        "ept_acme-corp_content-viewer",
                        "ept_acme-corp_content-author",
                        "epg_document-generator",
                        "eps_tenant_manager",
                    ),
                ),
            ),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `merges groups and flat-roles attributes into a single principal`() {
        val principal = service(existingUser()).provision(
            oauth2User(
                mapOf(
                    "groups" to listOf("/epistola/tenants/acme-corp/content-viewer"),
                    "roles" to listOf("ept_acme-corp_content-author", "epg_document-generator"),
                ),
            ),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
    }

    @Test
    fun `honours a custom flat-roles claim name`() {
        val customProperties = AuthProperties(flatRoles = FlatRolesProperties(claimName = "app_roles"))

        val principal = service(existingUser(), authProperties = customProperties).provision(
            oauth2User(mapOf("app_roles" to listOf("ept_acme-corp_tenant-administrator"))),
            "keycloak",
        )

        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")]).containsExactly(TenantRole.TENANT_ADMINISTRATOR)
    }

    @Test
    fun `ignores flat roles when configured claim name does not match`() {
        // Default config reads "roles" — strings in "app_roles" should be ignored.
        val principal = service(existingUser()).provision(
            oauth2User(mapOf("app_roles" to listOf("ept_acme-corp_content-viewer"))),
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
                    tenantMemberships = mapOf(TenantKey.of("resolved-tenant") to setOf(TenantRole.CONTENT_AUTHOR)),
                    globalRoles = setOf(TenantRole.CONTENT_VIEWER),
                )
            }
        }

        val principal = service(existingUser(), membershipResolver = resolver).provision(
            oauth2User(emptyMap()),
            "keycloak",
        )

        assertThat(resolverCalls).containsExactly("user@example.com")
        assertThat(principal.tenantMemberships[TenantKey.of("resolved-tenant")])
            .containsExactly(TenantRole.CONTENT_AUTHOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.CONTENT_VIEWER)
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
            oauth2User(mapOf("roles" to listOf("ept_acme-corp_content-viewer"))),
            "keycloak",
        )

        assertThat(resolverCalls).isEmpty()
    }

    @Test
    fun `syncs token memberships when any are present`() {
        val syncCalls = mutableListOf<Map<TenantKey, Set<TenantRole>>>()

        service(existingUser(), syncCalls = syncCalls).provision(
            oauth2User(mapOf("roles" to listOf("ept_acme-corp_content-viewer", "ept_beta-org_content-author"))),
            "keycloak",
        )

        assertThat(syncCalls).hasSize(1)
        assertThat(syncCalls.single()[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(syncCalls.single()[TenantKey.of("beta-org")]).containsExactly(TenantRole.CONTENT_AUTHOR)
    }

    // --- Provider derivation (provider-neutral: any non-Keycloak registration is GENERIC_OIDC) ---

    @Test
    fun `deriveAuthProvider maps keycloak registration to KEYCLOAK`() {
        assertThat(OAuth2UserProvisioningService.deriveAuthProvider("keycloak")).isEqualTo(AuthProvider.KEYCLOAK)
        // Case-insensitive.
        assertThat(OAuth2UserProvisioningService.deriveAuthProvider("Keycloak")).isEqualTo(AuthProvider.KEYCLOAK)
    }

    @Test
    fun `deriveAuthProvider maps authentik and other registrations to GENERIC_OIDC`() {
        assertThat(OAuth2UserProvisioningService.deriveAuthProvider("authentik")).isEqualTo(AuthProvider.GENERIC_OIDC)
        assertThat(OAuth2UserProvisioningService.deriveAuthProvider("okta")).isEqualTo(AuthProvider.GENERIC_OIDC)
        assertThat(OAuth2UserProvisioningService.deriveAuthProvider("oidc")).isEqualTo(AuthProvider.GENERIC_OIDC)
    }

    @Test
    fun `provisions a new user via a non-keycloak registration as GENERIC_OIDC`() {
        val created = mutableListOf<CreateUser>()
        val newUser = existingUser().copy(provider = AuthProvider.GENERIC_OIDC)
        val mediator = object : Mediator {
            @Suppress("UNCHECKED_CAST")
            override fun <R> send(command: Command<R>): R = when (command) {
                is UpdateLastLogin -> Unit as R
                is CreateUser -> {
                    created.add(command)
                    newUser as R
                }
                else -> error("Unexpected command: $command")
            }

            @Suppress("UNCHECKED_CAST")
            override fun <R> query(query: Query<R>): R = when (query) {
                is GetUserByExternalId -> null as R // first login: user does not exist yet
                else -> error("Unexpected query: $query")
            }
        }
        val service = OAuth2UserProvisioningService(mediator, AuthProperties(autoProvision = true), null)

        val principal = service.provision(oauth2User(emptyMap()), "authentik")

        assertThat(created).hasSize(1)
        assertThat(created.single().provider).isEqualTo(AuthProvider.GENERIC_OIDC)
        assertThat(principal.email).isEqualTo("user@example.com")
    }
}
