package app.epistola.suite.security

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ApiKeyAuthenticationToken
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.apikeys.commands.RevokeApiKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant

/**
 * Integration test for [ApiKeyAuthenticationFilter].
 *
 * The filter dispatches its persistence operations through the mediator
 * (`LookupApiKeyByHash`, `RecordApiKeyUsage` — both `SystemInternal`), so it
 * needs a real `MediatorContext` and database. We bind both via [withMediator]
 * and seed keys with the [CreateApiKey] / [RevokeApiKey] commands.
 */
class ApiKeyAuthenticationFilterTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var apiKeyService: ApiKeyService

    private val meterRegistry = SimpleMeterRegistry()
    private val filter by lazy { ApiKeyAuthenticationFilter(apiKeyService, meterRegistry) }

    private lateinit var request: MockHttpServletRequest
    private lateinit var response: MockHttpServletResponse
    private var filterChainCalled = false
    private val filterChain = FilterChain { _, _ -> filterChainCalled = true }

    @BeforeEach
    fun setup() {
        request = MockHttpServletRequest()
        response = MockHttpServletResponse()
        filterChainCalled = false
        SecurityContextHolder.clearContext()
    }

    @Nested
    inner class NoApiKeyHeader {
        @Test
        fun `passes through when no X-API-Key header`() = withMediator {
            filter.doFilter(request, response, filterChain)

            assertThat(filterChainCalled).isTrue()
            assertThat(SecurityContextHolder.getContext().authentication).isNull()
        }

        @Test
        fun `passes through when header is blank`() = withMediator {
            request.addHeader("X-API-Key", "  ")

            filter.doFilter(request, response, filterChain)

            assertThat(filterChainCalled).isTrue()
        }
    }

    @Nested
    inner class InvalidApiKey {
        @Test
        fun `returns 401 for key without epk_ prefix`() = withMediator {
            request.addHeader("X-API-Key", "invalid_key_format")

            filter.doFilter(request, response, filterChain)

            assertThat(response.status).isEqualTo(401)
            assertThat(filterChainCalled).isFalse()
            assertThat(response.contentAsString).contains("Invalid API key format")
        }

        @Test
        fun `returns 401 for unknown key hash`() = withMediator {
            request.addHeader("X-API-Key", "epk_unknown_key_that_doesnt_exist")

            filter.doFilter(request, response, filterChain)

            assertThat(response.status).isEqualTo(401)
            assertThat(filterChainCalled).isFalse()
            assertThat(response.contentAsString).contains("Invalid API key")
        }

        @Test
        fun `returns 401 for revoked key`() = fixture {
            lateinit var plaintextKey: String
            lateinit var tenant: Tenant

            given {
                tenant = tenant("Filter Revoked")
                val created = CreateApiKey(tenantId = tenant.id, name = "Will Revoke").execute()
                plaintextKey = created.plaintextKey
                RevokeApiKey(tenantId = tenant.id, id = created.apiKey.id).execute()
            }

            whenever {
                request.addHeader("X-API-Key", plaintextKey)
                filter.doFilter(request, response, filterChain)
            }

            then {
                assertThat(response.status).isEqualTo(401)
                assertThat(filterChainCalled).isFalse()
                assertThat(response.contentAsString).contains("disabled")
            }
        }

        @Test
        fun `returns 401 for expired key`() = fixture {
            lateinit var plaintextKey: String
            lateinit var tenant: Tenant

            given {
                tenant = tenant("Filter Expired")
                val created = CreateApiKey(
                    tenantId = tenant.id,
                    name = "Already Expired",
                    expiresAt = Instant.now().minusSeconds(3600),
                ).execute()
                plaintextKey = created.plaintextKey
            }

            whenever {
                request.addHeader("X-API-Key", plaintextKey)
                filter.doFilter(request, response, filterChain)
            }

            then {
                assertThat(response.status).isEqualTo(401)
                assertThat(filterChainCalled).isFalse()
                assertThat(response.contentAsString).contains("expired")
            }
        }
    }

    @Nested
    inner class ValidApiKey {
        @Test
        fun `authenticates and passes through for valid key`() = fixture {
            lateinit var plaintextKey: String
            lateinit var tenant: Tenant

            given {
                tenant = tenant("Filter Valid")
                plaintextKey = CreateApiKey(tenantId = tenant.id, name = "Valid Key").execute().plaintextKey
            }

            whenever {
                request.addHeader("X-API-Key", plaintextKey)
                filter.doFilter(request, response, filterChain)
            }

            then {
                assertThat(filterChainCalled).isTrue()
                val auth = SecurityContextHolder.getContext().authentication
                assertThat(auth).isNotNull
                assertThat(auth).isInstanceOf(ApiKeyAuthenticationToken::class.java)
                assertThat(auth!!.isAuthenticated).isTrue()
            }
        }

        @Test
        fun `creates principal with correct tenant membership`() = fixture {
            lateinit var plaintextKey: String
            lateinit var tenant: Tenant

            given {
                tenant = tenant("Filter Tenant Membership")
                plaintextKey = CreateApiKey(tenantId = tenant.id, name = "Membership").execute().plaintextKey
            }

            whenever {
                request.addHeader("X-API-Key", plaintextKey)
                filter.doFilter(request, response, filterChain)
            }

            then {
                val auth = SecurityContextHolder.getContext().authentication as ApiKeyAuthenticationToken
                val principal = auth.principal
                assertThat(principal.tenantMemberships.keys).containsExactly(tenant.id)
                assertThat(principal.currentTenantId).isEqualTo(tenant.id)
            }
        }

        @Test
        fun `creates NPA identity from API key`() = fixture {
            lateinit var plaintextKey: String
            lateinit var tenant: Tenant

            given {
                tenant = tenant("Filter NPA")
                plaintextKey = CreateApiKey(tenantId = tenant.id, name = "My Integration").execute().plaintextKey
            }

            whenever {
                request.addHeader("X-API-Key", plaintextKey)
                filter.doFilter(request, response, filterChain)
            }

            then {
                val auth = SecurityContextHolder.getContext().authentication as ApiKeyAuthenticationToken
                val principal = auth.principal
                assertThat(principal.displayName).isEqualTo("My Integration")
                assertThat(principal.externalId).startsWith("apikey:")
                assertThat(principal.email).contains("@npa.epistola")
            }
        }

        @Test
        fun `key with future expiry is accepted`() = fixture {
            lateinit var plaintextKey: String
            lateinit var tenant: Tenant

            given {
                tenant = tenant("Filter Future Expiry")
                plaintextKey = CreateApiKey(
                    tenantId = tenant.id,
                    name = "Future",
                    expiresAt = Instant.now().plusSeconds(86400),
                ).execute().plaintextKey
            }

            whenever {
                request.addHeader("X-API-Key", plaintextKey)
                filter.doFilter(request, response, filterChain)
            }

            then {
                assertThat(filterChainCalled).isTrue()
                assertThat(SecurityContextHolder.getContext().authentication).isNotNull
            }
        }
    }
}
