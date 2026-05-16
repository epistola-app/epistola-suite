package app.epistola.suite.api.security

import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.commands.RecordApiKeyUsage
import app.epistola.suite.apikeys.queries.LookupApiKeyByHash
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.commands.EnsureUser
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates API requests using the X-API-Key header.
 *
 * This filter is NOT a @Component — it is registered explicitly by SecurityConfig
 * into the API security filter chain only (paths under /api).
 *
 * If the header is present, the filter validates the key and sets up the
 * SecurityContext with an [ApiKeyAuthenticationToken]. If the key is invalid
 * or expired, a 401 JSON response is returned immediately.
 *
 * If no X-API-Key header is present, the request passes through to the
 * next filter (e.g., OAuth2 resource server or form login fallback).
 *
 * Persistence operations are dispatched via the mediator using `SystemInternal`
 * messages ([LookupApiKeyByHash], [RecordApiKeyUsage]) — `MediatorFilter` runs
 * at `Ordered.HIGHEST_PRECEDENCE`, so `MediatorContext` is bound by the time
 * this filter executes inside the Spring Security chain.
 *
 * ## Async dispatches
 *
 * `OncePerRequestFilter` defaults to skipping ASYNC re-dispatches. We override
 * that because async-using endpoints (currently the Spring AI MCP server's
 * `/api/mcp` SSE transport) re-run the entire filter chain when the deferred
 * result completes; without re-establishing `SecurityContextHolder` on the
 * async thread, Spring Security's `AuthorizationFilter` denies. To avoid
 * a second DB lookup and double counting, the validated principal is cached
 * in a request attribute on the original REQUEST dispatch and restored on
 * the async re-dispatch — `RecordApiKeyUsage` and the success counter run
 * exactly once per request.
 */
class ApiKeyAuthenticationFilter(
    private val apiKeyService: ApiKeyService,
    private val meterRegistry: MeterRegistry,
    private val headerName: String = DEFAULT_HEADER_NAME,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun authCounter(result: String): Counter = Counter.builder("epistola.api.auth.attempts")
        .tag("result", result)
        .register(meterRegistry)

    /** Run on async re-dispatches too — see class KDoc. */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // On async re-dispatches, restore the principal we cached on REQUEST.
        // Skips the DB lookup, the usage record, and the metric counter — this
        // is the same logical request, the side effects already happened.
        val cachedPrincipal = request.getAttribute(REQUEST_ATTR_PRINCIPAL) as? EpistolaPrincipal
        if (cachedPrincipal != null) {
            SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(cachedPrincipal)
            filterChain.doFilter(request, response)
            return
        }

        val apiKeyHeader = request.getHeader(headerName)

        if (apiKeyHeader.isNullOrBlank()) {
            authCounter("no_header").increment()
            filterChain.doFilter(request, response)
            return
        }

        if (!apiKeyHeader.startsWith(ApiKeyService.KEY_PREFIX)) {
            authCounter("invalid_format").increment()
            writeUnauthorized(response, "Invalid API key format")
            return
        }

        val keyHash = apiKeyService.hashKey(apiKeyHeader)
        val apiKey = LookupApiKeyByHash(keyHash).query()

        if (apiKey == null) {
            authCounter("invalid_key").increment()
            writeUnauthorized(response, "Invalid API key")
            return
        }

        if (!apiKey.isUsable()) {
            val reason = if (!apiKey.enabled) {
                authCounter("disabled").increment()
                "API key is disabled"
            } else {
                authCounter("expired").increment()
                "API key has expired"
            }
            writeUnauthorized(response, reason)
            return
        }

        // Update last used timestamp asynchronously (best-effort)
        try {
            RecordApiKeyUsage(apiKey.id).execute()
        } catch (e: Exception) {
            log.debug("Failed to update API key last_used_at: {}", e.message)
        }

        // Every API key is its own non-human service identity. Audit columns
        // (created_by / updated_by) are real FKs to users(id), so the key's
        // service-account row must exist before this request performs any
        // write. Idempotent; mirrors how LocalUserDetailsService provisions a
        // config user on login. NOT best-effort: a missing users row would only
        // surface later as a confusing FK violation mid-handler.
        EnsureUser(
            id = UserKey.of(apiKey.id.value),
            externalId = "apikey:${apiKey.id}",
            email = "apikey-${apiKey.keyPrefix}@npa.epistola",
            displayName = apiKey.name,
            provider = AuthProvider.API_KEY,
        ).execute()

        val principal = EpistolaPrincipal(
            userId = UserKey.of(apiKey.id.value),
            externalId = "apikey:${apiKey.id}",
            email = "apikey-${apiKey.keyPrefix}@npa.epistola",
            displayName = apiKey.name,
            tenantMemberships = mapOf(apiKey.tenantKey to TenantRole.entries.toSet()),
            currentTenantId = apiKey.tenantKey,
        )

        SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(principal)
        // Cache for the async re-dispatch — see class KDoc.
        request.setAttribute(REQUEST_ATTR_PRINCIPAL, principal)
        authCounter("success").increment()

        filterChain.doFilter(request, response)
    }

    private fun writeUnauthorized(response: HttpServletResponse, message: String) {
        log.debug("API key authentication failed: {}", message)
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"code":"UNAUTHORIZED","message":"$message"}""")
    }

    companion object {
        const val DEFAULT_HEADER_NAME = "X-API-Key"

        /** Request attribute key for the validated principal. */
        const val REQUEST_ATTR_PRINCIPAL = "app.epistola.suite.api.security.ApiKeyAuthenticationFilter.PRINCIPAL"
    }
}
