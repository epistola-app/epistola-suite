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
 * Every refused authorization answers the contract's one 403 type, `forbidden`, and its members say
 * what was refused (contract `ForbiddenProblemDetail`, ADR 0048 section 6). A missing permission is
 * covered in [EpistolaTemplateApiIT]; this class covers the other two shapes.
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
class ForbiddenProblemApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a key of another tenant is refused naming the tenant alone`() {
        val (_, key) = seedTenantAndKey()
        val other = seedTenantAndKey().first

        val response = restTemplate.exchange("/api/tenants/${other.value}", HttpMethod.GET, HttpEntity<Void>(headers(key)), String::class.java)

        val json = forbidden(response)
        assertThat(JsonPath.read<String>(json, "$.tenantKey")).isEqualTo(other.value)
        assertThat(JsonPath.read<Map<String, Any?>>(json, "$")).doesNotContainKeys("requiredPermission", "requiredPlatformRole", "reason", "tenantId")
    }

    @Test
    fun `a tenant key is refused a platform operation naming the platform role`() {
        val (_, key) = seedTenantAndKey()
        val body = """{"key": "denied-${UUID.randomUUID().toString().take(8)}", "name": "Denied"}"""

        val response = restTemplate.exchange("/api/tenants", HttpMethod.POST, HttpEntity(body, headers(key)), String::class.java)

        val json = forbidden(response)
        assertThat(JsonPath.read<String>(json, "$.requiredPlatformRole")).isEqualTo("TENANT_MANAGER")
        assertThat(JsonPath.read<Map<String, Any?>>(json, "$")).doesNotContainKeys("requiredPermission", "tenantKey", "reason", "requiredRole")
    }

    private fun forbidden(response: ResponseEntity<String>): String {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val json = response.body!!
        assertThat(JsonPath.read<String>(json, "$.type")).isEqualTo("https://epistola.app/errors/forbidden")
        assertThat(JsonPath.read<String>(json, "$.title")).isEqualTo("Forbidden")
        assertThat(JsonPath.read<Int>(json, "$.status")).isEqualTo(403)
        return json
    }

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 forbidden-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("forb-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Forbidden Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "forbidden-it", roles = TenantRole.entries.toSet()).execute()
        tenantKey to created.plaintextKey
    }
}
