// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.RequestEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.client.RestTemplate
import org.springframework.web.filter.OncePerRequestFilter
import java.net.HttpURLConnection

/**
 * Proves the demo landing over real HTTP.
 *
 * The claim under test is an ordering one — `GET /` is declared by both
 * [app.epistola.suite.tenants.TenantRoutes] and [DemoLandingRoutes], and the demo one has to win.
 * That depends on `RouterFunctionMapping` sorting router beans by `@Order` and taking the first
 * match, which is not visible from either class in isolation, so it is worth an actual request.
 *
 * Binds its own principal rather than importing `TestSecurityContextConfiguration`: the shared test
 * principal has no tenant memberships, which is exactly the case that falls through.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    DemoLandingRedirectIT.PersonalTenantPrincipalConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=true",
        "epistola.generation.polling.enabled=false",
    ],
)
class DemoLandingRedirectIT : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    /**
     * `TestRestTemplate` follows redirects, which would turn the 303 under test into the 200 of the
     * page it points at — indistinguishable from the fall-through. This one stops at the redirect.
     */
    private val noFollow = RestTemplate(
        object : SimpleClientHttpRequestFactory() {
            override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                super.prepareConnection(connection, httpMethod)
                connection.instanceFollowRedirects = false
            }
        },
    )

    private fun getRoot(vararg headers: Pair<String, String>) = noFollow.exchange(
        RequestEntity.get("http://localhost:$port/")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build(),
        String::class.java,
    )

    @Test
    fun `sends a demo user straight to their own tenant`() {
        withMediator { CreateTenant(id = PERSONAL_KEY, name = PERSONAL_EMAIL).execute() }

        val response = getRoot()

        assertThat(response.statusCode).isEqualTo(HttpStatus.SEE_OTHER)
        assertThat(response.headers.location.toString()).isEqualTo("/tenants/${PERSONAL_KEY.value}")
    }

    @Test
    fun `falls back to the tenant list when there is no personal tenant to open`() {
        // Same request, a principal without that membership — the picker, unchanged.
        val response = getRoot(NO_PERSONAL_TENANT_HEADER to "true")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("<title>Tenants")
    }

    /**
     * Binds a principal whose email derives to [PERSONAL_KEY] and which is a member of it — the
     * shape [DemoLoginMembershipResolver] produces at login.
     */
    @TestConfiguration
    class PersonalTenantPrincipalConfig {
        @Bean
        @Order(-98)
        fun personalTenantPrincipalFilter(): OncePerRequestFilter = object : OncePerRequestFilter() {
            override fun doFilterInternal(
                request: HttpServletRequest,
                response: HttpServletResponse,
                filterChain: FilterChain,
            ) {
                val memberships = if (request.getHeader(NO_PERSONAL_TENANT_HEADER) != null) {
                    emptyMap()
                } else {
                    mapOf(PERSONAL_KEY to TenantRole.entries.toSet())
                }
                val principal = EpistolaPrincipal(
                    userId = TestPrincipalUser.ID,
                    externalId = TestPrincipalUser.EXTERNAL_ID,
                    email = PERSONAL_EMAIL,
                    displayName = "Demo Landing",
                    tenantMemberships = memberships,
                    globalRoles = TenantRole.entries.toSet(),
                    platformRoles = emptySet(),
                    currentTenantId = null,
                )
                val secCtx = SecurityContextHolder.createEmptyContext()
                secCtx.authentication = TestingAuthenticationToken(principal, "N/A", listOf())
                SecurityContextHolder.setContext(secCtx)
                try {
                    if (SecurityContext.isBound()) {
                        filterChain.doFilter(request, response)
                    } else {
                        SecurityContext.runWithPrincipal(principal) { filterChain.doFilter(request, response) }
                    }
                } finally {
                    SecurityContextHolder.clearContext()
                }
            }
        }
    }

    companion object {
        private const val NO_PERSONAL_TENANT_HEADER = "X-Test-No-Personal-Tenant"
        private const val PERSONAL_EMAIL = "landing@demo.test"
        private val PERSONAL_KEY: TenantKey =
            DemoLoginMembershipResolver.deriveTenantKeyFromEmail(PERSONAL_EMAIL)!!
    }
}
