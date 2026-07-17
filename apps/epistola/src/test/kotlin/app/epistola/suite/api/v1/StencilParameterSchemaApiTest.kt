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
 * REST API (issue #384). Verifies the field round-trips through the create,
 * create-version, and update-draft write paths and the version read path.
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
    fun `create stencil with parameterSchema echoes it back on the version`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-create-${UUID.randomUUID().toString().take(8)}"

        val create = post(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils",
            """{"id":"$stencilId","name":"Param Stencil","parameterSchema":$schema}""",
            apiKey,
        )
        assertThat(create.statusCode).isEqualTo(HttpStatus.CREATED)

        val version = get(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils/$stencilId/versions/1",
            apiKey,
        )
        assertThat(version.statusCode).describedAs(version.body).isEqualTo(HttpStatus.OK)
        assertSchema(version.body)
    }

    @Test
    fun `create stencil version with parameterSchema round-trips`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-version-${UUID.randomUUID().toString().take(8)}"

        val base = "/api/tenants/${tenantKey.value}/catalogs/default/stencils"
        post(base, """{"id":"$stencilId","name":"Param Stencil"}""", apiKey)

        // A new version can only be started once the current draft is published;
        // otherwise createStencilVersion idempotently returns the existing draft.
        val published = post("$base/$stencilId/versions/1/publish", "", apiKey)
        assertThat(published.statusCode).describedAs(published.body).isEqualTo(HttpStatus.OK)

        val created = post("$base/$stencilId/versions", """{"parameterSchema":$schema}""", apiKey)
        assertThat(created.statusCode).describedAs(created.body).isEqualTo(HttpStatus.CREATED)
        val versionId = JsonPath.read<Int>(created.body, "$.id")

        val version = get(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils/$stencilId/versions/$versionId",
            apiKey,
        )
        assertThat(version.statusCode).describedAs(version.body).isEqualTo(HttpStatus.OK)
        assertSchema(version.body)
    }

    @Test
    fun `create stencil version treats omitted or null parameterSchema as no parameters`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-clear-${UUID.randomUUID().toString().take(8)}"

        val base = "/api/tenants/${tenantKey.value}/catalogs/default/stencils"
        val createdStencil = post(
            base,
            """{"id":"$stencilId","name":"Param Stencil","parameterSchema":$schema}""",
            apiKey,
        )
        assertThat(createdStencil.statusCode).describedAs(createdStencil.body).isEqualTo(HttpStatus.CREATED)

        val publishedV1 = post("$base/$stencilId/versions/1/publish", "", apiKey)
        assertThat(publishedV1.statusCode).describedAs(publishedV1.body).isEqualTo(HttpStatus.OK)

        val omitted = post("$base/$stencilId/versions", "{}", apiKey)
        assertThat(omitted.statusCode).describedAs(omitted.body).isEqualTo(HttpStatus.CREATED)
        assertNoSchema(omitted.body)
        assertThat(JsonPath.read<String>(omitted.body, "$.content.root")).isEqualTo("root")

        val publishedV2 = post("$base/$stencilId/versions/2/publish", "", apiKey)
        assertThat(publishedV2.statusCode).describedAs(publishedV2.body).isEqualTo(HttpStatus.OK)

        val explicitNull = post("$base/$stencilId/versions", """{"parameterSchema":null}""", apiKey)
        assertThat(explicitNull.statusCode).describedAs(explicitNull.body).isEqualTo(HttpStatus.CREATED)
        assertNoSchema(explicitNull.body)
        assertThat(JsonPath.read<String>(explicitNull.body, "$.content.root")).isEqualTo("root")
    }

    @Test
    fun `update draft parameterSchema round-trips`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "param-update-${UUID.randomUUID().toString().take(8)}"

        post(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils",
            """{"id":"$stencilId","name":"Param Stencil"}""",
            apiKey,
        )

        // Reuse the server's own serialization of the empty draft content so the
        // PATCH body carries valid content without hand-authoring the graph.
        val versionPath = "/api/tenants/${tenantKey.value}/catalogs/default/stencils/$stencilId/versions/1"
        val content = JsonPath.parse(get(versionPath, apiKey).body).read<Any>("$.content")
        val contentJson = Configuration.defaultConfiguration().jsonProvider().toJson(content)

        val updated = patch(
            versionPath,
            """{"content":$contentJson,"parameterSchema":$schema}""",
            apiKey,
        )
        assertThat(updated.statusCode).describedAs(updated.body).isEqualTo(HttpStatus.OK)

        val reread = get(versionPath, apiKey)
        assertThat(reread.statusCode).describedAs(reread.body).isEqualTo(HttpStatus.OK)
        assertSchema(reread.body)
    }

    @Test
    fun `stencil without parameters reports no parameterSchema`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val stencilId = "no-param-${UUID.randomUUID().toString().take(8)}"

        post(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils",
            """{"id":"$stencilId","name":"Plain Stencil"}""",
            apiKey,
        )

        val response = get(
            "/api/tenants/${tenantKey.value}/catalogs/default/stencils/$stencilId/versions/1",
            apiKey,
        )
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val lenient = Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS)
        assertThat(JsonPath.using(lenient).parse(response.body).read<Any?>("$.parameterSchema")).isNull()
    }

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

    private fun patch(path: String, body: String, apiKey: String) = restTemplate.exchange(path, HttpMethod.PATCH, HttpEntity(body, headers(apiKey)), String::class.java)

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
