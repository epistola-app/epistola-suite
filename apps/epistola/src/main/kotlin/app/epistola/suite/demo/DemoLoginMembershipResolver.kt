// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.LoginMembershipResolver
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.ResolvedMemberships
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.users.User
import app.epistola.suite.users.commands.SyncTenantMemberships
import app.epistola.suite.validation.DuplicateIdException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.util.Locale

/**
 * Demo-only: gives every person who logs in their own private tenant.
 *
 * When a user logs in without any `/epistola/` group memberships, their **whole email address** is
 * slugified into a tenant key (`sander@degroot.dev` → `sander-degroot-dev`). If that tenant does not
 * exist it is created and seeded with the bundled demo catalog plus a staging and a production
 * environment, so the person lands in a working sandbox rather than an empty shell. They get every
 * [TenantRole] on it — and, deliberately, **no** [PlatformRole] and no global roles, so they can
 * neither see another person's tenant ([app.epistola.suite.tenants.queries.ListTenants] filters on
 * membership) nor create further tenants ([CreateTenant] requires `TENANT_MANAGER`). Roles the
 * identity provider does grant still win: this resolver is only consulted when the token carried
 * none, and platform roles from the token survive it
 * (see [app.epistola.suite.security.OAuth2UserProvisioningService]).
 *
 * It used to key off the email *domain*, which put everyone from one company into a shared tenant
 * where they overwrote each other's work. A demo is a place to try things, so the unit is the person.
 *
 * This component is only active when `epistola.demo.enabled=true`.
 * To remove demo mode, delete the entire `demo` package and the two wiring lines it owns in
 * [app.epistola.suite.config.SecurityConfig].
 */
@Component
@ConditionalOnProperty(name = ["epistola.demo.enabled"], havingValue = "true")
class DemoLoginMembershipResolver(
    private val mediator: Mediator,
    private val transactionTemplate: TransactionTemplate,
) : LoginMembershipResolver {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun resolve(email: String, user: User): ResolvedMemberships? {
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        val tenantKey = claimTenantKey(normalizedEmail) ?: return null
        val roles = TenantRole.entries.toSet()

        ensureTenant(tenantKey, normalizedEmail)
        persistMembership(user, tenantKey, roles)

        log.info("Demo mode: assigned user {} to personal tenant {} with all roles", email, tenantKey.value)
        return ResolvedMemberships(tenantMemberships = mapOf(tenantKey to roles))
    }

    /**
     * Picks the tenant key this email owns.
     *
     * Slugifying is lossy — `j.doe+test@acme.io` and `j.doe.test@acme.io` both reduce to
     * `j-doe-test-acme-io` — so the readable key is only used when it is free or already this
     * person's. The tenant's `name` is the email that created it, which is what makes "already
     * this person's" answerable. Anyone who loses the race falls back to a key carrying a hash of
     * their address, so two people never silently share a sandbox.
     */
    private fun claimTenantKey(email: String): TenantKey? {
        val preferred = deriveTenantKeyFromEmail(email) ?: return null
        return SecurityContext.runWithPrincipal(SYSTEM_PRINCIPAL) {
            val existing = mediator.query(GetTenant(preferred))
            when {
                existing == null || existing.name == email -> preferred
                else -> hashedTenantKeyForEmail(email).also {
                    log.info("Demo mode: {} is taken by another address, falling back to {}", preferred.value, it?.value)
                }
            }
        }
    }

    private fun ensureTenant(tenantKey: TenantKey, email: String) {
        SecurityContext.runWithPrincipal(SYSTEM_PRINCIPAL) {
            if (mediator.query(GetTenant(tenantKey)) == null) {
                createTenant(tenantKey, email)
            }
            // Outside the existence check on purpose: EnsureSubscribedCatalog is an idempotent
            // install/no-op/upgrade state machine, so re-asserting it costs a classpath read and a
            // fingerprint compare, and it means a catalog that failed to install on the first login
            // is not a permanently empty sandbox.
            seedDemoCatalog(tenantKey)
        }
    }

    private fun createTenant(tenantKey: TenantKey, email: String) {
        log.info("Demo mode: auto-creating personal tenant {} for {}", tenantKey.value, email)
        try {
            // Tenant and environments commit together: a half-built tenant would be worse than none,
            // and every later login would skip past it as already existing.
            transactionTemplate.executeWithoutResult {
                mediator.send(CreateTenant(id = tenantKey, name = email))
                val tenantId = TenantId(tenantKey)
                mediator.send(CreateEnvironment(EnvironmentId(EnvironmentKey.of("staging"), tenantId), "Staging"))
                mediator.send(CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), tenantId), "Production"))
            }
        } catch (e: DuplicateIdException) {
            // Two tabs finishing OIDC at once. First login is now the common case rather than a rare
            // one, so losing this race must not fail the login — the winner built the same tenant.
            log.info("Demo mode: tenant {} was created concurrently: {}", tenantKey.value, e.message)
        }
    }

    /**
     * Installs the bundled demo catalog so the tenant has templates, themes and stencils to look at.
     *
     * Best-effort: a catalog that fails to install is a worse demo, but a login that fails because of
     * it is no demo at all. Same trade-off [DemoLoader.ensureQualityDemo] makes.
     */
    private fun seedDemoCatalog(tenantKey: TenantKey) {
        try {
            val result = mediator.send(EnsureSubscribedCatalog(tenantKey = tenantKey, sourceUrl = DemoLoader.DEMO_CATALOG_URL))
            log.debug("Demo mode: catalog {} in {} is {}", result.catalogKey.value, tenantKey.value, result.status)
        } catch (e: Exception) {
            log.warn("Demo mode: could not seed the demo catalog into {}: {}", tenantKey.value, e.message)
        }
    }

    /**
     * Writes the grant to `tenant_memberships`.
     *
     * [ResolvedMemberships] alone only lives in the session principal, so before this the tenant row
     * outlived the membership that justified it — the tenant was absent from its own member list, and
     * from any later login that did not come back through this resolver. Despite the name,
     * [SyncTenantMemberships] only upserts the rows it is given; it never removes a grant made
     * elsewhere.
     */
    private fun persistMembership(user: User, tenantKey: TenantKey, roles: Set<TenantRole>) {
        try {
            mediator.send(SyncTenantMemberships(user.id, mapOf(tenantKey to roles)))
        } catch (e: Exception) {
            // The session principal already carries the grant, so the login still works.
            log.warn("Demo mode: could not persist membership of {} in {}: {}", user.email, tenantKey.value, e.message)
        }
    }

    companion object {
        /** [TenantKey]'s own upper bound. */
        private const val MAX_SLUG_LENGTH = 63
        private const val HASH_SUFFIX_LENGTH = 6

        /** Longest stem that still leaves room for `-` plus [HASH_SUFFIX_LENGTH] hex characters. */
        private const val MAX_HASHED_STEM_LENGTH = MAX_SLUG_LENGTH - HASH_SUFFIX_LENGTH - 1

        private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")

        /**
         * Slugifies a whole email address into a [TenantKey]: `sander@degroot.dev` →
         * `sander-degroot-dev`, `j.doe+test@acme.io` → `j-doe-test-acme-io`.
         *
         * Local part and domain are slugified separately and both must survive, so an address whose
         * local part is entirely non-ASCII (`日本@example.jp`) does not quietly collapse to the
         * domain and hand every such user the same tenant. [TenantKey] stays the sole authority on
         * what is valid — anything produced here is offered to [TenantKey.validateOrNull], and a
         * rejection falls back to [hashedTenantKeyForEmail].
         *
         * Expects an already lowercased, trimmed address. Returns null when there is nothing usable
         * on one side of the `@`.
         */
        internal fun deriveTenantKeyFromEmail(email: String): TenantKey? {
            val local = email.substringBefore('@', "").slugify()
            val domain = email.substringAfter('@', "").slugify()
            if (local.isBlank() || domain.isBlank()) return null

            val slug = "$local-$domain".truncateSlug(MAX_SLUG_LENGTH)
            val candidate = if (slug.first().isLetter()) slug else "u-$slug".truncateSlug(MAX_SLUG_LENGTH)
            return TenantKey.validateOrNull(candidate) ?: hashedTenantKeyForEmail(email)
        }

        /**
         * The collision-proof form: a readable stem plus a hash of the full address. Used when the
         * plain slug is invalid, or when another address already owns it.
         */
        internal fun hashedTenantKeyForEmail(email: String): TenantKey? {
            val local = email.substringBefore('@', "").slugify()
            val domain = email.substringAfter('@', "").slugify()
            if (local.isBlank() || domain.isBlank()) return null

            val stem = "$local-$domain".truncateSlug(MAX_HASHED_STEM_LENGTH)
            val prefixed = if (stem.isNotEmpty() && stem.first().isLetter()) stem else "u-$stem".truncateSlug(MAX_HASHED_STEM_LENGTH)
            return TenantKey.validateOrNull("$prefixed-${shortHash(email)}")
        }

        private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(HASH_SUFFIX_LENGTH)

        private fun String.slugify(): String = lowercase(Locale.ROOT).replace(NON_SLUG_CHARS, "-").trim('-')

        /** Truncating can leave a trailing `-`, so re-trim afterwards. */
        private fun String.truncateSlug(max: Int): String = take(max).trim('-')

        /** Bootstrap principal for tenant creation — [CreateTenant] requires `TENANT_MANAGER`. */
        private val SYSTEM_PRINCIPAL = EpistolaPrincipal(
            userId = SystemUser.ID,
            externalId = SystemUser.EXTERNAL_ID,
            email = SystemUser.EMAIL,
            displayName = SystemUser.DISPLAY_NAME,
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )
    }
}
