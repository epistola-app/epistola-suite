package app.epistola.suite.security

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ApiKeyAuthenticationToken
import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.TenantKey
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

@Tag("unit")
class ApiKeyAuthenticationFilterTest {

    private val apiKeyService = ApiKeyService()
    private val storedKeys = mutableMapOf<String, ApiKey>()
    private val repository = TestApiKeyRepository(storedKeys)
    private val filter = ApiKeyAuthenticationFilter(repository, apiKeyService)
    private lateinit var request: MockHttpServletRequest
    private lateinit var response: MockHttpServletResponse
    private var filterChainCalled = false
    private val filterChain = FilterChain { _, _ -> filterChainCalled = true }

    @BeforeEach
    fun setup() {
        request = MockHttpServletRequest()
        response = MockHttpServletResponse()
        filterChainCalled = false
        storedKeys.clear()
        SecurityContextHolder.clearContext()
    }

    @Nested
    inner class NoApiKeyHeader {
        @Test
        fun `passes through when no X-API-Key header`() {
            filter.doFilter(request, response, filterChain)

            assertThat(filterChainCalled).isTrue()
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        @Test
        fun `passes through when header is blank`() {
            request.addHeader("X-API-Key", "  ")

            filter.doFilter(request, response, filterChain)

            assertThat(filterChainCalled).isTrue()
        }
    }

    @Nested
    inner class InvalidApiKey {
        @Test
        fun `returns 401 for key without epk_ prefix`() {
            request.addHeader("X-API-Key", "invalid_key_format")

            filter.doFilter(request, response, filterChain)

            assertThat(response.status).isEqualTo(401)
            assertThat(filterChainCalled).isFalse()
            assertThat(response.contentAsString).contains("Invalid API key format")
        }

        @Test
        fun `returns 401 for unknown key hash`() {
            request.addHeader("X-API-Key", "epk_unknown_key_that_doesnt_exist")

            filter.doFilter(request, response, filterChain)

            assertThat(response.status).isEqualTo(401)
            assertThat(filterChainCalled).isFalse()
            assertThat(response.contentAsString).contains("Invalid API key")
        }

        @Test
        fun `returns 401 for disabled key`() {
            val key = createApiKey(enabled = false)
            request.addHeader("X-API-Key", key)

            filter.doFilter(request, response, filterChain)

            assertThat(response.status).isEqualTo(401)
            assertThat(filterChainCalled).isFalse()
            assertThat(response.contentAsString).contains("disabled")
        }

        @Test
        fun `returns 401 for expired key`() {
            val key = createApiKey(expiresAt = Instant.now().minusSeconds(3600))
            request.addHeader("X-API-Key", key)

            filter.doFilter(request, response, filterChain)

            assertThat(response.status).isEqualTo(401)
            assertThat(filterChainCalled).isFalse()
            assertThat(response.contentAsString).contains("expired")
        }
    }

    @Nested
    inner class ValidApiKey {
        @Test
        fun `authenticates and passes through for valid key`() {
            val key = createApiKey()
            request.addHeader("X-API-Key", key)

            filter.doFilter(request, response, filterChain)

            assertThat(filterChainCalled).isTrue()
            val auth = SecurityContextHolder.getContext().authentication
            assertThat(auth).isNotNull()
            assertThat(auth).isInstanceOf(ApiKeyAuthenticationToken::class.java)
            assertThat(auth!!.isAuthenticated).isTrue()
        }

        @Test
        fun `creates principal with correct tenant membership`() {
            val tenantId = TenantKey.of("test-tenant")
            val key = createApiKey(tenantId = tenantId)
            request.addHeader("X-API-Key", key)

            filter.doFilter(request, response, filterChain)

            val auth = SecurityContextHolder.getContext().authentication as ApiKeyAuthenticationToken
            val principal = auth.principal
            assertThat(principal.tenantMemberships).containsExactly(tenantId)
            assertThat(principal.currentTenantId).isEqualTo(tenantId)
        }

        @Test
        fun `creates NPA identity from API key`() {
            val key = createApiKey(name = "My Integration")
            request.addHeader("X-API-Key", key)

            filter.doFilter(request, response, filterChain)

            val auth = SecurityContextHolder.getContext().authentication as ApiKeyAuthenticationToken
            val principal = auth.principal
            assertThat(principal.displayName).isEqualTo("My Integration")
            assertThat(principal.externalId).startsWith("apikey:")
            assertThat(principal.email).contains("@npa.epistola")
        }

        @Test
        fun `key with future expiry is accepted`() {
            val key = createApiKey(expiresAt = Instant.now().plusSeconds(86400))
            request.addHeader("X-API-Key", key)

            filter.doFilter(request, response, filterChain)

            assertThat(filterChainCalled).isTrue()
            assertThat(SecurityContextHolder.getContext().authentication).isNotNull()
        }
    }

    private fun createApiKey(
        tenantId: TenantKey = TenantKey.of("test-tenant"),
        name: String = "Test Key",
        enabled: Boolean = true,
        expiresAt: Instant? = null,
    ): String {
        val plaintextKey = apiKeyService.generateKey()
        val keyHash = apiKeyService.hashKey(plaintextKey)
        val keyPrefix = apiKeyService.extractPrefix(plaintextKey)

        val apiKey = ApiKey(
            id = ApiKeyKey.of(UUID.randomUUID()),
            tenantKey = tenantId,
            name = name,
            keyPrefix = keyPrefix,
            enabled = enabled,
            createdAt = Instant.now(),
            lastUsedAt = null,
            expiresAt = expiresAt,
            createdBy = null,
        )

        storedKeys[keyHash] = apiKey
        return plaintextKey
    }
}
