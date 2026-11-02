// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import com.jayway.jsonpath.JsonPath
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Operations contract 2.0.0-alpha.6 added that the suite does not support yet answer
 * `501 operation-not-implemented`, as the contract asks, rather than 404 (an unmapped route) or a
 * request accepted with part of it dropped: tenant search (ADR 0043), quality profiles (ADR 0046),
 * and a `quality` binding in a template request.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
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
@ActiveProfiles("test")
class NotImplementedOperationsApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `searching a tenant answers 501`() {
        val (tenantKey, key) = seedTenantAndKey()

        val response = restTemplate.exchange("/api/tenants/${tenantKey.value}/search?q=letter", HttpMethod.GET, HttpEntity<Void>(headers(key)), String::class.java)

        assertNotImplemented(response, "searchTenant")
    }

    @Test
    fun `listing a release's quality profiles answers 501`() {
        val (tenantKey, key) = seedTenantAndKey()
        val draft = withMediator { CatalogApiPaths.version(tenantKey) }

        val response = restTemplate.exchange("$draft/quality-profiles", HttpMethod.GET, HttpEntity<Void>(headers(key)), String::class.java)

        assertNotImplemented(response, "listQualityProfiles")
    }

    @Test
    fun `a template created with a quality binding is refused with 501, and not created`() {
        val (tenantKey, key) = seedTenantAndKey()
        val draft = withMediator { CatalogApiPaths.version(tenantKey) }
        val body = """{"key": "bound", "name": "Bound", "quality": {"profiles": [{"key": "house-style"}]}}"""

        val response = restTemplate.exchange("$draft/templates", HttpMethod.POST, HttpEntity(body, headers(key)), String::class.java)

        assertNotImplemented(response, "createTemplate with a quality binding")
        val get = restTemplate.exchange("$draft/templates/bound", HttpMethod.GET, HttpEntity<Void>(headers(key)), String::class.java)
        assertThat(get.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun assertNotImplemented(response: ResponseEntity<String>, operation: String) {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(HttpStatus.NOT_IMPLEMENTED)
        val json = response.body!!
        assertThat(JsonPath.read<String>(json, "$.type")).isEqualTo("https://epistola.app/errors/operation-not-implemented")
        assertThat(JsonPath.read<String>(json, "$.operation")).isEqualTo(operation)
    }

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 not-implemented-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("ni-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Not Implemented Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "not-implemented-it", roles = TenantRole.entries.toSet()).execute()
        tenantKey to created.plaintextKey
    }
}
