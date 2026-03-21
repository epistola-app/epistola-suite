package app.epistola.suite.demo

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.LoginMembershipResolver
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.ResolvedMemberships
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.users.User
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Demo-only: resolves tenant memberships from the user's email domain.
 *
 * When a user logs in without any `ep_` group memberships, their email domain
 * is converted to a tenant key (e.g., `user@acme.io` → `acme-io`). If the
 * tenant doesn't exist, it's auto-created. The user gets all roles.
 *
 * This component is only active when `epistola.demo.enabled=true`.
 * To remove demo mode, delete the entire `demo` package.
 */
@Component
@ConditionalOnProperty(name = ["epistola.demo.enabled"], havingValue = "true")
class DemoLoginMembershipResolver(
    private val mediator: Mediator,
) : LoginMembershipResolver {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun resolve(email: String, user: User): ResolvedMemberships? {
        val tenantKey = deriveTenantKeyFromEmail(email) ?: return null
        ensureTenantExists(tenantKey)
        log.info("Demo mode: assigned user {} to tenant {} with all roles", email, tenantKey.value)
        return ResolvedMemberships(
            tenantMemberships = mapOf(tenantKey to TenantRole.entries.toSet()),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        )
    }

    private fun deriveTenantKeyFromEmail(email: String): TenantKey? {
        val domain = email.substringAfter('@', "").lowercase()
        if (domain.isBlank()) return null
        val slug = domain.replace('.', '-')
        return TenantKey.validateOrNull(slug)
    }

    private fun ensureTenantExists(tenantKey: TenantKey) {
        val existing = mediator.query(GetTenant(tenantKey))
        if (existing != null) return

        log.info("Demo mode: auto-creating tenant {}", tenantKey.value)
        SecurityContext.runWithPrincipal(SYSTEM_PRINCIPAL) {
            mediator.send(CreateTenant(id = tenantKey, name = tenantKey.value))
        }
    }

    companion object {
        private val SYSTEM_PRINCIPAL = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-000000000001"),
            externalId = "system",
            email = "system@epistola.app",
            displayName = "System",
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )
    }
}
