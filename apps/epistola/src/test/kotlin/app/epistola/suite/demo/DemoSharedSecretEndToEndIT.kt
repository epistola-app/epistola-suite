// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.queries.GetUserByExternalId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Proves the demo shared secret over real HTTP, through the real filter chain.
 *
 * A filter-level unit test cannot cover this. Two things only fail when the filters are actually
 * chained: [app.epistola.suite.api.security.ApiKeyAuthenticationFilter] rejects a non-`epk_`
 * credential with a 401 and stops the chain, and the demo filter has to be ordered ahead of it.
 * Both would pass in isolation and 401 in production.
 *
 * See [DemoSharedSecretSecurityConfig] for why the chain has to be re-declared here.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    DemoSharedSecretSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "epistola.generation.polling.enabled=false",
    ],
)
@AutoConfigureTestRestTemplate
class DemoSharedSecretEndToEndIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    /**
     * A tenant the shared secret has no key for and no membership of. Built once — installing a
     * tenant's system catalog is not free, and every test here targets the same one.
     */
    private val targetTenant by lazy { createTenant(TARGET_TENANT_NAME) }

    private fun getTenant(credential: String?): ResponseEntity<String> {
        val tenantKey = targetTenant.id

        val headers = HttpHeaders().apply {
            credential?.let { set(HttpHeaders.AUTHORIZATION, it) }
            set(HttpHeaders.USER_AGENT, "curl/8.0")
        }
        return restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )
    }

    @Test
    fun `the shared secret reaches a tenant it holds no key for`() {
        val response = getTenant("ApiKey $SHARED_SECRET")

        // The secret's principal has no membership of this tenant at all — it passes on globalRoles.
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains(TARGET_TENANT_NAME)
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertThat(getTenant("apikey $SHARED_SECRET").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a wrong secret is still rejected`() {
        // The demo filter must decline rather than allow, and leave the API-key filter to answer.
        assertThat(getTenant("ApiKey not-the-secret").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a secret that is a prefix of the real one is rejected`() {
        assertThat(getTenant("ApiKey ${SHARED_SECRET.dropLast(1)}").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `an unknown api key is still rejected`() {
        // Regression guard: the demo filter must not swallow or alter the normal key path.
        assertThat(getTenant("ApiKey epk_notarealkey0000000000000000000000000").statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a real api key still authenticates while the demo filter is in the chain`() {
        // The headline compatibility question: adding a filter ahead of ApiKeyAuthenticationFilter
        // must leave ordinary key auth exactly as it was.
        val key = withMediator {
            CreateApiKey(tenantId = targetTenant.id, name = "Regression key").execute()
        }

        val response = getTenant("ApiKey ${key.plaintextKey}")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains(TARGET_TENANT_NAME)
    }

    @Test
    fun `a real api key is still confined to its own tenant`() {
        // And must not be quietly elevated by sharing a chain with an all-tenant credential.
        val otherTenant = createTenant("Some Other Tenant")
        val key = withMediator {
            CreateApiKey(tenantId = otherTenant.id, name = "Other tenant key").execute()
        }

        val response = restTemplate.exchange(
            "/api/tenants/${targetTenant.id.value}",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set(HttpHeaders.AUTHORIZATION, "ApiKey ${key.plaintextKey}") }),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `no credential is still rejected`() {
        assertThat(getTenant(null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a bearer token is left to the resource server`() {
        // Not an ApiKey scheme, so the demo filter must not look at it.
        assertThat(getTenant("Bearer some.jwt.token").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the secret provisions its own service account for audit trails`() {
        getTenant("ApiKey $SHARED_SECRET")

        val npa = withMediator {
            GetUserByExternalId(
                DemoSharedSecretAuthenticationFilter.SHARED_SECRET_EXTERNAL_ID,
                AuthProvider.API_KEY,
            ).query()
        }
        assertThat(npa).isNotNull()
        assertThat(npa!!.id).isEqualTo(DemoSharedSecretAuthenticationFilter.SHARED_SECRET_USER_ID)
    }

    companion object {
        /**
         * Comfortably over [DemoProperties.MIN_SHARED_SECRET_LENGTH], and deliberately
         * unmistakable and low-entropy — a realistic-looking one trips secret scanners.
         */
        const val SHARED_SECRET = "not-a-real-secret-only-used-in-tests"
        private const val TARGET_TENANT_NAME = "Shared Secret Target"
    }
}
