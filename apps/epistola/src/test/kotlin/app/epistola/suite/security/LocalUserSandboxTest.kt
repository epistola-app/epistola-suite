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
import app.epistola.suite.users.commands.EnsureUser
import app.epistola.suite.users.queries.GetUserByExternalId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * A local user either administers a tenant someone chose, or gets a private one derived from their
 * username. Both shapes are wanted at the same time — an operator account beside training accounts
 * — so the choice is per user rather than per profile.
 *
 * The fallback matters as much as the feature: `application-local.yaml` is read under `local` and
 * `local,demo` alike, and only the latter supplies a resolver. A user asking for a sandbox where
 * none can be made has to still be able to log in.
 */
@Tag("unit")
class LocalUserSandboxTest {

    private val sandboxTenant = TenantKey.of("trainee1-ab12cd")

    private fun user() = User(
        id = UserKey.generate(),
        externalId = "trainee1@demo",
        email = "trainee1@demo",
        displayName = "Trainee One",
        provider = AuthProvider.LOCAL,
        tenantMemberships = emptyMap(),
        enabled = true,
        createdAt = OffsetDateTime.now(),
        lastLoginAt = null,
    )

    private fun mediator(user: User) = object : Mediator {
        @Suppress("UNCHECKED_CAST")
        override fun <R> send(command: Command<R>): R = when (command) {
            is EnsureUser -> Unit as R
            else -> error("Unexpected command: $command")
        }

        @Suppress("UNCHECKED_CAST")
        override fun <R> query(query: Query<R>): R = when (query) {
            is GetUserByExternalId -> user as R
            else -> error("Unexpected query: $query")
        }
    }

    private fun service(
        sandbox: Boolean,
        resolver: LoginMembershipResolver?,
    ): LocalUserDetailsService {
        val properties = AuthProperties(
            localUsers = listOf(
                LocalUserProperties(
                    username = "trainee1@demo",
                    password = "trainee1",
                    displayName = "Trainee One",
                    tenant = "demo",
                    sandbox = sandbox,
                    roles = setOf(TenantRole.CONTENT_AUTHOR),
                ),
            ),
        )
        return LocalUserDetailsService(properties, mediator(user()), resolver)
    }

    private fun resolverGiving(tenant: TenantKey) = object : LoginMembershipResolver {
        override fun resolve(email: String, user: User) = ResolvedMemberships(
            tenantMemberships = mapOf(tenant to TenantRole.entries.toSet()),
        )
    }

    private fun principalFor(sandbox: Boolean, resolver: LoginMembershipResolver?) = (service(sandbox, resolver).loadUserByUsername("trainee1@demo") as EpistolaPrincipalHolder)
        .epistolaPrincipal

    @Test
    fun `a sandbox user lands in the tenant the resolver derives, not the configured one`() {
        val principal = principalFor(sandbox = true, resolver = resolverGiving(sandboxTenant))

        assertThat(principal.currentTenantId).isEqualTo(sandboxTenant)
        // The configured membership is replaced rather than merged: a leftover membership of the
        // shared tenant would defeat the point of a sandbox.
        assertThat(principal.tenantMemberships.keys).containsExactly(sandboxTenant)
    }

    @Test
    fun `a user without sandbox keeps the tenant it was configured with`() {
        val principal = principalFor(sandbox = false, resolver = resolverGiving(sandboxTenant))

        assertThat(principal.currentTenantId).isEqualTo(TenantKey.of("demo"))
        assertThat(principal.tenantMemberships.keys).containsExactly(TenantKey.of("demo"))
    }

    @Test
    fun `asking for a sandbox where none can be made falls back rather than failing the login`() {
        // The shape under plain `local`: same config file, no resolver bean.
        val principal = principalFor(sandbox = true, resolver = null)

        assertThat(principal.currentTenantId).isEqualTo(TenantKey.of("demo"))
    }
}
