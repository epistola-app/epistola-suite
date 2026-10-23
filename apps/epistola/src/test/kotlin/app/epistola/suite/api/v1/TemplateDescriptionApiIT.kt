// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
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

/** A template's description over REST: set on create, read back, kept, changed, removed and bounded. */
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
class TemplateDescriptionApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a template's description is stored, kept, changed and removed`() {
        val (tenantKey, key) = seedTenantAndKey()
        val templates = "${draft(tenantKey)}/templates"

        val created = call(HttpMethod.POST, templates, key, """{"key": "letter", "name": "Letter", "description": "For residents"}""")
        assertThat(created.statusCode).`as`(created.body).isEqualTo(HttpStatus.CREATED)
        assertThat(JsonPath.read<String>(created.body!!, "$.description")).isEqualTo("For residents")

        val list = call(HttpMethod.GET, templates, key, null)
        assertThat(JsonPath.read<List<String>>(list.body!!, "$.items[*].description")).containsExactly("For residents")

        val renamed = call(HttpMethod.PATCH, "$templates/letter", key, """{"name": "Letter 2"}""")
        assertThat(JsonPath.read<String>(renamed.body!!, "$.description")).`as`("omitted, so kept").isEqualTo("For residents")

        val changed = call(HttpMethod.PATCH, "$templates/letter", key, """{"description": "For companies"}""")
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$templates/letter", key, null).body!!, "$.description")).isEqualTo("For companies")
        assertThat(changed.statusCode).isEqualTo(HttpStatus.OK)

        val removed = call(HttpMethod.PATCH, "$templates/letter", key, """{"description": ""}""")
        assertThat(JsonPath.read<Any?>(removed.body!!, "$.description")).isNull()
    }

    @Test
    fun `a description over 2000 characters is refused`() {
        val (tenantKey, key) = seedTenantAndKey()

        val response = call(HttpMethod.POST, "${draft(tenantKey)}/templates", key, """{"key": "letter", "name": "Letter", "description": "${"x".repeat(2001)}"}""")

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private fun call(method: HttpMethod, url: String, apiKey: String, body: String?): ResponseEntity<String> = restTemplate.exchange(url, method, HttpEntity(body, headers(apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.5.0 template-description-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("tdesc-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenantKey, "Template Description Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "tdesc-it").execute().plaintextKey
    }

    private fun draft(tenantKey: TenantKey): String = withMediator { CatalogApiPaths.version(tenantKey, CatalogKey.DEFAULT.value) }
}
