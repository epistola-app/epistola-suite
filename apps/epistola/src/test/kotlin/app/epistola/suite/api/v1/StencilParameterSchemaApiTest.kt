// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
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
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * HTTP-level coverage of the stencil `parameterSchema` exposed over the public
 * REST API (issue #384). Verifies the field round-trips through the create and
 * working-copy write paths, survives marking ready, and reads back from the working copy.
 *
 * Parameters are an intrinsic property of every stencil (there is no feature
 * toggle); the REST surface — like MCP and the internal handler — accepts and
 * returns `parameterSchema` unconditionally.
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
class StencilParameterSchemaApiTest : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val schema =
        """{"type":"object","properties":{"recipientName":{"type":"string"}},"required":["recipientName"]}"""

    @Test
    fun `create stencil with parameterSchema echoes it back on the working copy`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-create-${UUID.randomUUID().toString().take(8)}"

        val create = post(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils",
            """{"id":"$stencilId","name":"Param Stencil","parameterSchema":$schema}""",
            apiKey,
        )
        assertThat(create.statusCode).isEqualTo(HttpStatus.CREATED)

        val content = get(contentPath(tenantKey, stencilId), apiKey)
        assertThat(content.statusCode).describedAs(content.body).isEqualTo(HttpStatus.OK)
        assertSchema(content.body)
    }

    @Test
    fun `updating the working copy round-trips the parameterSchema, and marking ready keeps it`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-update-${UUID.randomUUID().toString().take(8)}"
        post("/api/tenants/${tenantKey.value}/catalogs/default/stencils", """{"id":"$stencilId","name":"Param Stencil"}""", apiKey)
        val path = contentPath(tenantKey, stencilId)

        val updated = put(path, """{"content":${contentOf(path, apiKey)},"parameterSchema":$schema}""", apiKey)
        assertThat(updated.statusCode).describedAs(updated.body).isEqualTo(HttpStatus.OK)
        assertSchema(updated.body)

        val ready = post(path.removeSuffix("/content") + "/mark-ready", "", apiKey)
        assertThat(ready.statusCode).describedAs(ready.body).isEqualTo(HttpStatus.OK)
        assertSchema(JsonPath.parse(ready.body).jsonString().let { Configuration.defaultConfiguration().jsonProvider().toJson(JsonPath.parse(it).read<Any>("$.stencil")) })
        assertSchema(get(path, apiKey).body)
    }

    @Test
    fun `updating the working copy with an omitted or null parameterSchema clears it`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-clear-${UUID.randomUUID().toString().take(8)}"
        post(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils",
            """{"id":"$stencilId","name":"Param Stencil","parameterSchema":$schema}""",
            apiKey,
        )
        val path = contentPath(tenantKey, stencilId)
        val content = contentOf(path, apiKey)

        val omitted = put(path, """{"content":$content}""", apiKey)
        assertThat(omitted.statusCode).describedAs(omitted.body).isEqualTo(HttpStatus.OK)
        assertNoSchema(omitted.body)

        put(path, """{"content":$content,"parameterSchema":$schema}""", apiKey)
        val explicitNull = put(path, """{"content":$content,"parameterSchema":null}""", apiKey)
        assertThat(explicitNull.statusCode).describedAs(explicitNull.body).isEqualTo(HttpStatus.OK)
        assertNoSchema(explicitNull.body)
        assertThat(JsonPath.read<String>(explicitNull.body, "$.content.root")).isEqualTo("root")
    }

    @Test
    fun `stencil without parameters reports no parameterSchema`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "no-param-${UUID.randomUUID().toString().take(8)}"
        post("/api/tenants/${tenantKey.value}/catalogs/default/stencils", """{"id":"$stencilId","name":"Plain Stencil"}""", apiKey)

        val response = get(contentPath(tenantKey, stencilId), apiKey)
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        assertNoSchema(response.body)
    }

    private fun contentPath(tenantKey: TenantKey, stencilId: String) = "/api/tenants/${tenantKey.value}/catalogs/default/stencils/$stencilId/content"

    /** The server's own serialization of the working copy's content, so a PUT body carries valid content. */
    private fun contentOf(path: String, apiKey: String): String = Configuration.defaultConfiguration().jsonProvider().toJson(JsonPath.parse(get(path, apiKey).body).read<Any>("$.content"))

    private fun assertNoSchema(body: String?) {
        val lenient = Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS)
        assertThat(JsonPath.using(lenient).parse(body).read<Any?>("$.parameterSchema")).isNull()
    }

    private fun assertSchema(body: String?) {
        val json = JsonPath.parse(body)
        assertThat(json.read<String>("$.parameterSchema.properties.recipientName.type")).isEqualTo("string")
        assertThat(json.read<List<String>>("$.parameterSchema.required")).containsExactly("recipientName")
    }

    private fun post(path: String, body: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers(apiKey)), String::class.java)

    private fun put(path: String, body: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.PUT, HttpEntity(body, headers(apiKey)), String::class.java)

    private fun get(path: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<String>(null, headers(apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.9.0 stencil-param-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("sp-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Stencil Param Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "sp-it").execute()
        tenantKey to created.plaintextKey
    }
}
