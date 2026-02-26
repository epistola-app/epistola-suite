package app.epistola.suite.api.security

import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
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
 */
class ApiKeyAuthenticationFilter(
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyService: ApiKeyService,
    private val headerName: String = DEFAULT_HEADER_NAME,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val apiKeyHeader = request.getHeader(headerName)

        if (apiKeyHeader.isNullOrBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        if (!apiKeyHeader.startsWith(ApiKeyService.KEY_PREFIX)) {
            writeUnauthorized(response, "Invalid API key format")
            return
        }

        val keyHash = apiKeyService.hashKey(apiKeyHeader)
        val apiKey = apiKeyRepository.findByKeyHash(keyHash)

        if (apiKey == null) {
            writeUnauthorized(response, "Invalid API key")
            return
        }

        if (!apiKey.isUsable()) {
            val reason = if (!apiKey.enabled) "API key is disabled" else "API key has expired"
            writeUnauthorized(response, reason)
            return
        }

        // Update last used timestamp asynchronously (best-effort)
        try {
            apiKeyRepository.updateLastUsed(apiKey.id)
        } catch (e: Exception) {
            log.debug("Failed to update API key last_used_at: {}", e.message)
        }

        val principal = EpistolaPrincipal(
            userId = UserKey.of(apiKey.id.value),
            externalId = "apikey:${apiKey.id}",
            email = "apikey-${apiKey.keyPrefix}@npa.epistola",
            displayName = apiKey.name,
            tenantMemberships = setOf(apiKey.tenantId),
            currentTenantId = apiKey.tenantId,
        )

        SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken(principal)

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
    }
}
