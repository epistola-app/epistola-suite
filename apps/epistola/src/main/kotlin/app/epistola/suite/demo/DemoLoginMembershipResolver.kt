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
        // Normalized here for the tenant's display name; [deriveTenantKeyFromEmail] normalizes the
        // key independently, so the two cannot drift.
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        val tenantKey = deriveTenantKeyFromEmail(normalizedEmail) ?: return null
        val roles = TenantRole.entries.toSet()

        ensureTenant(tenantKey, normalizedEmail)
        persistMembership(user, tenantKey, roles)

        log.info("Demo mode: assigned user {} to personal tenant {} with all roles", email, tenantKey.value)
        return ResolvedMemberships(tenantMemberships = mapOf(tenantKey to roles))
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

        /** 24 bits — ample for the number of tenants a demo installation will ever hold. */
        private const val HASH_LENGTH = 6

        /** Stands in for a label that cannot lead a [TenantKey]: empty, or starting with a digit. */
        private const val LABEL_FALLBACK = "u"

        private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")

        /**
         * Derives this address's tenant key: the local part for identification, then a short hash of
         * the whole address — `sander@degroot.dev` → `sander-6196c7`.
         *
         * Every key is hashed, not just the ones that would otherwise clash. Deriving a readable key
         * and only hashing on collision meant a second code path, a "is this key already someone
         * else's?" lookup on every login, and a special case for each way slugifying can go wrong.
         * Hashing unconditionally makes uniqueness a property of the key rather than something to
         * check for, so the label in front of it is free to be whatever is readable — a reserved
         * word (`admin@acme.io` → `admin-…`), or nothing at all (`日本@example.jp` → `u-…`).
         *
         * The label is the **local part only**. The hash covers the whole address, so
         * `sander@a.com` and `sander@b.com` are already distinct without spending key length on the
         * domain.
         *
         * Normalizes the address itself rather than trusting the caller to have done it, so
         * `Sander@Degroot.dev` and `sander@degroot.dev` cannot become two tenants, and so the recipe
         * below holds for whatever is passed in. Returns null only when one side of the `@` is
         * missing entirely — that is not an address.
         *
         * The full derivation is written out step by step, with a reference implementation and a
         * worked example per branch, under "The key derivation, exactly" in `docs/auth.md`. Every
         * step is pinned by `DemoTenantKeyDerivationTest`, so changing anything here without
         * updating that section will fail a test — please keep it that way.
         */
        internal fun deriveTenantKeyFromEmail(rawEmail: String): TenantKey? {
            val email = rawEmail.trim().lowercase(Locale.ROOT)
            val local = email.substringBefore('@', "")
            val domain = email.substringAfter('@', "")
            if (local.isBlank() || domain.isBlank()) return null

            val label = local.slugify()
            // [TenantKey] must start with a letter. The label leads, so it is the only part that can
            // break that — an empty one (nothing ASCII to slugify) or one starting with a digit.
            val stem = when {
                label.isBlank() -> LABEL_FALLBACK
                label.first().isLetter() -> label
                else -> "$LABEL_FALLBACK-$label"
            }
            // Truncated after prefixing, so the fallback cannot push the key over the limit.
            val head = stem.truncateSlug(MAX_SLUG_LENGTH - HASH_LENGTH - 1)
            return TenantKey.validateOrNull("$head-${shortHash(email)}")
        }

        /**
         * The first [HASH_LENGTH] hex characters of `sha256(email)`.
         *
         * Deliberately something anyone can reproduce without the application — no salt, no secret,
         * no installation-specific input — so an operator can work out which tenant an address maps
         * to from a shell prompt:
         *
         * ```
         * printf %s "sander@degroot.dev" | shasum -a 256 | cut -c1-6   # 665cdb
         * ```
         *
         * The input is the address **lowercased and trimmed** (see [resolve]), hashed as UTF-8. This
         * is an identifier, not a credential: it exists so two addresses cannot land in one tenant,
         * and nothing is protected by its being hard to guess. Documented in `docs/auth.md`.
         */
        private fun shortHash(email: String): String = MessageDigest.getInstance("SHA-256")
            .digest(email.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(HASH_LENGTH)

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
