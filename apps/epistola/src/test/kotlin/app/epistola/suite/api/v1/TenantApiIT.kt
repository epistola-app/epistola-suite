package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
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
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

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
class TenantApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `update tenant renames it and returns the updated DTO`() {
        val (tenantKey, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.PATCH,
            HttpEntity("""{"name":"Renamed Tenant"}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.id")).isEqualTo(tenantKey.value)
        assertThat(JsonPath.read<String>(body, "$.name")).isEqualTo("Renamed Tenant")

        // Read-your-write: a subsequent GET reflects the new name.
        val get = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(apiKey)),
            String::class.java,
        )
        assertThat(JsonPath.read<String>(get.body!!, "$.name")).isEqualTo("Renamed Tenant")
    }

    @Test
    fun `update tenant with a blank name returns 400 validation problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.PATCH,
            HttpEntity("""{"name":"   "}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
    }

    @Test
    fun `unsupported tenant method returns 405 problem details with Allow header`() {
        val (tenantKey, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        assertThat(response.headers.allow).contains(HttpMethod.GET, HttpMethod.PATCH, HttpMethod.DELETE)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/method-not-allowed")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(405)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/method-not-allowed")
        assertThat(JsonPath.read<String>(body, "$.method")).isEqualTo("POST")
        assertThat(JsonPath.read<List<String>>(body, "$.supportedMethods")).contains("GET", "PATCH", "DELETE")
    }

    @Test
    fun `malformed json returns stable problem detail`() {
        val (tenantKey, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.PATCH,
            HttpEntity("""{"name":""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.detail")).isEqualTo("Request body is malformed or unreadable")
    }

    @Test
    fun `missing required body fields returns bad request problem details`() {
        val (_, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/bad-request")
        assertThat(JsonPath.read<String>(body, "$.detail")).isEqualTo("Request body is malformed or unreadable")
        assertThat(JsonPath.read<String>(body, "$.instance")).isEqualTo("/api/tenants")
    }

    @Test
    fun `creating a tenant with an over-long name returns 400 validation problem details`() {
        val (_, apiKey) = seedTenantAndKey()
        val overLongName = "x".repeat(101)

        val response = restTemplate.exchange(
            "/api/tenants",
            HttpMethod.POST,
            HttpEntity("""{"id":"over-long-name-tenant","name":"$overLongName"}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/validation-error")
        assertThat(JsonPath.read<String>(body, "$.detail")).isEqualTo("Name must be 100 characters or less")
        assertThat(JsonPath.read<String>(body, "$.errors[0].field")).isEqualTo("name")
        assertThat(JsonPath.read<String>(body, "$.errors[0].message")).isEqualTo("Name must be 100 characters or less")
    }

    @Test
    fun `unsupported media type returns 415 problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val headers = baseHeaders(apiKey).apply {
            contentType = MediaType.TEXT_PLAIN
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.PATCH,
            HttpEntity("name=Renamed", headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/unsupported-media-type")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(415)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/unsupported-media-type")
        assertThat(JsonPath.read<String>(body, "$.contentType")).isEqualTo("text/plain")
    }

    @Test
    fun `unacceptable accept header returns 406 problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val headers = baseHeaders(apiKey).apply {
            accept = listOf(MediaType.APPLICATION_XML)
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}",
            HttpMethod.GET,
            HttpEntity<String>(null, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_ACCEPTABLE)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/not-acceptable")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(406)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/not-acceptable")
        assertThat(JsonPath.read<String>(body, "$.acceptHeader")).isEqualTo("application/xml")
    }

    @Test
    fun `invalid uuid path variable returns type mismatch problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/documents/jobs/not-a-uuid",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/type-mismatch")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/type-mismatch")
        assertThat(JsonPath.read<String>(body, "$.parameterName")).isEqualTo("requestId")
        assertThat(JsonPath.read<String>(body, "$.actualValue")).isEqualTo("not-a-uuid")
    }

    @Test
    fun `unknown api path returns 404 problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/not-a-real-api-path",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/not-found")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(404)
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/not-found")
        assertThat(JsonPath.read<String>(body, "$.instance")).isEqualTo("/api/tenants/${tenantKey.value}/not-a-real-api-path")
    }

    @Test
    fun `list on an unsorted endpoint rejects a sort key with 400 and an empty supported set`() {
        val (_, apiKey) = seedTenantAndKey()

        // The contract advertises sort/direction on every list endpoint, but the tenants list
        // supports no sortable columns — a non-null sort is rejected (not silently ignored) so a
        // caller can tell "sorting isn't supported here" from "sorting was applied". The empty
        // supportedValues signals the endpoint exposes no sort keys.
        val response = restTemplate.exchange(
            "/api/tenants?sort=name",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/unsupported-sort")
        assertThat(JsonPath.read<Int>(body, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(body, "$.value")).isEqualTo("name")
        assertThat(JsonPath.read<List<String>>(body, "$.supportedValues")).isEmpty()
    }

    @Test
    fun `list on an unsorted endpoint rejects an unrecognized direction with 400`() {
        val (_, apiKey) = seedTenantAndKey()

        // direction is validated uniformly across list endpoints, even where it has no effect.
        val response = restTemplate.exchange(
            "/api/tenants?direction=sideways",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/unsupported-sort-direction")
        assertThat(JsonPath.read<String>(body, "$.value")).isEqualTo("sideways")
        assertThat(JsonPath.read<List<String>>(body, "$.supportedValues")).containsExactly("asc", "desc")
    }

    @Test
    fun `list on an unsorted endpoint accepts an absent sort and the default direction`() {
        val (_, apiKey) = seedTenantAndKey()

        // No sort + the contract-default direction must pass untouched.
        val response = restTemplate.exchange(
            "/api/tenants?direction=desc",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.4.0-SNAPSHOT tenant-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("tenant-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Tenant API Test").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "tenant-it").execute()
        tenantKey to created.plaintextKey
    }
}
