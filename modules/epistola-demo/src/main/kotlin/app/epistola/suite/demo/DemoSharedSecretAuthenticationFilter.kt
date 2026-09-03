// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ApiPreAuthenticationFilter
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantRole
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.commands.EnsureUser
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Demo-only: authenticates every API endpoint as an all-tenant superuser when the caller presents the
 * configured demo shared secret.
 *
 * The demo website calls Epistola on behalf of whichever visitor is using it. Those visitors get a
 * tenant each (see [DemoLoginMembershipResolver]), created on the fly at login, so there is no
 * per-tenant API key to mint and track ahead of time. One credential that works everywhere is the
 * point.
 *
 * **This is a total bypass of the tenant and permission model.** The principal it installs carries
 * every [TenantRole] as a *global* role and every [PlatformRole], so
 * `SpringMediator.enforceAuthorization` passes for every tenant, including tenants that do not exist
 * yet. Three things keep that inside the demo: the bean only exists under the `demo` profile (see
 * [DemoSecurityConfiguration]), a configured secret in any other profile fails the boot (see
 * [DemoSharedSecretSafetyValidator]), and the secret has a minimum length.
 *
 * ## How it cooperates with [ApiKeyAuthenticationFilter]
 *
 * The secret is presented as `Authorization: ApiKey <secret>` so existing clients and SDKs need no
 * new code path — but that header is exactly what [ApiKeyAuthenticationFilter] claims, and it answers
 * `401 Invalid API key format` for anything not starting with `epk_` *without continuing the chain*.
 * So this filter does not authenticate the request itself. It validates the secret and publishes the
 * resulting principal on [ApiKeyAuthenticationFilter.REQUEST_ATTR_PRINCIPAL] — the documented
 * "already validated for this request" hand-off that the API-key filter reads first. The API-key
 * filter then builds the token, and the async re-dispatch path (MCP SSE) keeps working unchanged,
 * because it is the same attribute that path already relies on.
 *
 * Anything that is not the secret — a real `epk_…` key, a bearer token, no header at all — is left
 * untouched and answered by the API-key filter exactly as before. This filter never writes a
 * response and never rejects anything.
 *
 * It lives in this package rather than in `modules/rest-api` so the shipped REST module carries no
 * notion of a bypass credential.
 */
class DemoSharedSecretAuthenticationFilter(
    secret: String,
    private val meterRegistry: MeterRegistry,
) : OncePerRequestFilter(),
    ApiPreAuthenticationFilter {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The secret is kept only as its digest, and candidates are digested before comparison, which
     * makes [MessageDigest.isEqual] a fixed-width compare. Neither the secret's value nor its length
     * is observable in the time this takes.
     */
    private val secretDigest: ByteArray = sha256(secret)

    /**
     * The NPA row is a single fixed row, so provisioning it once per JVM avoids a database write on
     * every API call. Falls back to re-asserting it if that ever fails.
     */
    private val userProvisioned = AtomicBoolean(false)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val candidate = parseApiKeyScheme(request.getHeader(HttpHeaders.AUTHORIZATION))
        if (candidate == null || !matchesSecret(candidate)) {
            // Not ours. The API-key filter downstream decides what this credential is worth.
            filterChain.doFilter(request, response)
            return
        }

        ensureNpaUser()
        // Hand the validated principal to ApiKeyAuthenticationFilter — see the class KDoc for why
        // this filter does not set the SecurityContext itself.
        request.setAttribute(ApiKeyAuthenticationFilter.REQUEST_ATTR_PRINCIPAL, SHARED_SECRET_PRINCIPAL)
        authCounter().increment()
        log.debug("Demo shared secret accepted for {} {}", request.method, request.requestURI)

        filterChain.doFilter(request, response)
    }

    private fun matchesSecret(candidate: String): Boolean = MessageDigest.isEqual(sha256(candidate), secretDigest)

    /**
     * Audit columns (`created_by` / `updated_by`) are real foreign keys to `users(id)`, so the
     * service-account row must exist before this request writes anything. Idempotent, and NOT
     * best-effort: a missing row would otherwise surface as a confusing FK violation mid-handler.
     */
    private fun ensureNpaUser() {
        if (userProvisioned.get()) return
        EnsureUser(
            id = SHARED_SECRET_USER_ID,
            externalId = SHARED_SECRET_EXTERNAL_ID,
            email = SHARED_SECRET_EMAIL,
            displayName = SHARED_SECRET_DISPLAY_NAME,
            provider = AuthProvider.API_KEY,
        ).execute()
        userProvisioned.set(true)
    }

    /**
     * Extracts the credential from `Authorization: ApiKey <value>`, mirroring
     * [ApiKeyAuthenticationFilter]'s own parsing so both filters agree on what a credential is. Any
     * other scheme — or none — yields null.
     *
     * The legacy `X-API-Key` header that [ApiKeyAuthenticationFilter] still accepts is deliberately
     * not honoured here. It exists for integrations that predate the standard scheme; the demo
     * website is new code, and a bypass credential should have exactly one way in.
     */
    private fun parseApiKeyScheme(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val separator = trimmed.indexOfFirst { it.isWhitespace() }
        if (separator < 0) return null

        val scheme = trimmed.substring(0, separator)
        if (!scheme.equals(ApiKeyAuthenticationFilter.AUTHORIZATION_SCHEME_API_KEY, ignoreCase = true)) return null

        return trimmed.substring(separator + 1).trim().takeIf { it.isNotBlank() }
    }

    /** Shares the API auth meter so demo traffic shows up beside real key traffic. */
    private fun authCounter(): Counter = Counter.builder("epistola.api.auth.attempts")
        .tag("result", METRIC_RESULT)
        .register(meterRegistry)

    companion object {
        /** Fixed, obviously-synthetic id, distinct from [app.epistola.suite.security.SystemUser.ID]. */
        val SHARED_SECRET_USER_ID: UserKey = UserKey.of("00000000-0000-0000-0000-300000000001")
        const val SHARED_SECRET_EXTERNAL_ID = "demo-shared-secret"
        const val SHARED_SECRET_EMAIL = "demo-shared-secret@npa.epistola"
        const val SHARED_SECRET_DISPLAY_NAME = "Demo Shared Secret"

        const val METRIC_RESULT = "demo_shared_secret"

        /**
         * The all-tenant superuser identity.
         *
         * `globalRoles` rather than a membership map is what makes it work on tenants that do not
         * exist yet: [EpistolaPrincipal.hasAccessToTenant] is true whenever `globalRoles` is
         * non-empty, and `rolesFor()` unions them into every tenant. `currentTenantId` stays null —
         * the secret is not bound to a tenant, which is why `/api/mcp` and the partition block of
         * `POST /api/ping` (both of which read `currentTenantId`) are not usable with it.
         */
        val SHARED_SECRET_PRINCIPAL = EpistolaPrincipal(
            userId = SHARED_SECRET_USER_ID,
            externalId = SHARED_SECRET_EXTERNAL_ID,
            email = SHARED_SECRET_EMAIL,
            displayName = SHARED_SECRET_DISPLAY_NAME,
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )

        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    }
}
