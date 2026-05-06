package app.epistola.suite.api.security

import app.epistola.api.identity.ClientInfo
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Validates client identity headers on v0.3 endpoints that require them.
 *
 * Per the contract spec, every API request should carry:
 *   - `User-Agent` starting with `epistola-contract/<version>`,
 *   - `X-EP-Node-Id` (an opaque per-process identifier).
 *
 * On endpoints where the headers are mandatory for correctness — `/ping` and
 * `/generation/collect` — this filter rejects requests missing them with 400.
 * Elsewhere it logs at WARN if the headers are absent but lets the request
 * pass; existing v0.2 clients that haven't been bumped would otherwise
 * regress.
 *
 * The actual parsing uses the contract's [ClientInfo] helper, so the suite
 * automatically tracks any future header changes the contract introduces.
 *
 * Registered explicitly into the API security filter chain by SecurityConfig
 * (paths under `/api`).
 */
class ClientIdentityFilter : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val client = ClientInfo.from(request)
        val path = request.requestURI

        if (isMandatory(path)) {
            if (client.nodeId.isNullOrBlank()) {
                writeBadRequest(response, "Missing required header: ${ClientInfo.HEADER_NODE_ID}")
                return
            }
            if (client.contractVersion.isNullOrBlank()) {
                writeBadRequest(
                    response,
                    "Missing or malformed User-Agent: must start with 'epistola-contract/<version>'",
                )
                return
            }
        } else {
            // Soft validation on other endpoints — warn but don't break v0.2 clients.
            if (client.nodeId.isNullOrBlank()) {
                logger.debug("{} called without {} header", sanitizeForLog(path), ClientInfo.HEADER_NODE_ID)
            }
            if (client.contractVersion.isNullOrBlank()) {
                logger.debug("{} called without epistola-contract/* User-Agent", sanitizeForLog(path))
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun isMandatory(path: String): Boolean {
        // Mandatory exactly on the v0.3 endpoints that need this metadata.
        // /ping is global; collect is tenant-scoped.
        if (path == "/api/ping") return true
        return COLLECT_PATH_REGEX.matches(path)
    }

    private fun writeBadRequest(response: HttpServletResponse, message: String) {
        logger.debug("Client identity validation failed: {}", message)
        response.status = HttpServletResponse.SC_BAD_REQUEST
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"code":"BAD_REQUEST","message":"$message"}""")
    }

    // Strip control characters before interpolating user-influenced values into
    // log messages — a crafted requestURI could otherwise inject newlines and
    // forge log lines, or inject terminal escape sequences. The explicit
    // \r / \n replacements come first because that's the literal pattern
    // CodeQL's java/log-injection sanitizer model recognizes; the regex pass
    // then sweeps remaining control chars (ESC, NUL, BEL, …).
    private fun sanitizeForLog(value: String): String = value.replace("\r", "_").replace("\n", "_").replace(Regex("\\p{Cntrl}"), "_")

    private companion object {
        // Matches /api/tenants/<tenantId>/generation/collect — see contract spec/paths/generation-collect.yaml.
        private val COLLECT_PATH_REGEX = Regex("^/api/tenants/[^/]+/generation/collect/?$")
    }
}
