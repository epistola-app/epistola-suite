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

/**
 * HTTP-level coverage of the code-list CRUD surface introduced alongside the
 * bundled system catalog. Asserts shape + status codes + readOnly flagging.
 *
 * Domain logic is covered by the mediator-level tests (`CodeListCommandsTest`
 * etc.); this IT specifically verifies the REST controller's mapping,
 * exception translation, and the `system` catalog's read-only enforcement
 * through the API layer.
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
class CodeListApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `list code lists returns system catalog code lists with readOnly=true`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/code-lists",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        // Bundled system catalog ships three reserved code lists.
        val slugs: List<String> = JsonPath.read(body, "$.items[*].slug")
        assertThat(slugs).contains("bcp-47", "iso-639-1", "iso-3166-1-alpha2")
        // All system catalog rows are read-only at the API layer.
        val readOnlyFlags: List<Boolean> = JsonPath.read(body, "$.items[*].readOnly")
        assertThat(readOnlyFlags).allMatch { it }
        val catalogTypes: List<String> = JsonPath.read(body, "$.items[*].catalogType")
        assertThat(catalogTypes).allMatch { it == "SUBSCRIBED" }
    }

    @Test
    fun `get system code list returns full DTO with binding fields`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/code-lists/iso-639-1",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.slug")).isEqualTo("iso-639-1")
        assertThat(JsonPath.read<String>(body, "$.catalog")).isEqualTo("system")
        assertThat(JsonPath.read<String>(body, "$.catalogType")).isEqualTo("SUBSCRIBED")
        assertThat(JsonPath.read<Boolean>(body, "$.readOnly")).isTrue
        assertThat(JsonPath.read<String>(body, "$.sourceType")).isEqualTo("INLINE")
    }

    @Test
    fun `list entries on a system code list returns ISO 639-1 codes`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/code-lists/iso-639-1/entries",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val codes: List<String> = JsonPath.read(body, "$.items[*].code")
        // ISO 639-1 has ~180 entries; we just spot-check a few well-known ones.
        assertThat(codes).contains("en", "nl", "fr", "de")
    }

    @Test
    fun `create code list on system catalog returns 409`() {
        val (tenantKey, key) = seedTenantAndKey()
        val body = """
            {
              "slug": "should-not-stick",
              "displayName": "Won't land",
              "sourceType": "INLINE",
              "entries": [{ "code": "x", "label": "X" }]
            }
        """.trimIndent()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/code-lists",
            HttpMethod.POST,
            HttpEntity(body, baseHeaders(key)),
            String::class.java,
        )
        // SUBSCRIBED catalog → `requireCatalogEditable` throws
        // `CatalogReadOnlyException`, surfaced as RFC 7807 problem details.
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = response.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/catalog-read-only")
        assertThat(JsonPath.read<String>(problem, "$.title")).isEqualTo("Catalog Read Only")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(409)
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/catalog-read-only")
        assertThat(JsonPath.read<String>(problem, "$.instance")).isEqualTo("/api/tenants/${tenantKey.value}/catalogs/system/code-lists")
        assertThat(JsonPath.read<String>(problem, "$.detail")).contains("read-only catalog")
    }

    @Test
    fun `refresh inline code list returns 400 problem details`() {
        val (tenantKey, key) = seedTenantAndKey()
        val createBody = """
            {
              "slug": "inline-refresh-test",
              "displayName": "Inline Refresh Test",
              "sourceType": "INLINE",
              "entries": [{ "code": "x", "label": "X" }]
            }
        """.trimIndent()
        val createResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists",
            HttpMethod.POST,
            HttpEntity(createBody, baseHeaders(key)),
            String::class.java,
        )
        assertThat(createResp.statusCode).isEqualTo(HttpStatus.CREATED)

        val refreshResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/inline-refresh-test/refresh",
            HttpMethod.POST,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(refreshResp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refreshResp.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = refreshResp.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/code-list-not-refreshable")
        assertThat(JsonPath.read<String>(problem, "$.title")).isEqualTo("Code List Not Refreshable")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/code-list-not-refreshable")
        assertThat(JsonPath.read<String>(problem, "$.instance"))
            .isEqualTo("/api/tenants/${tenantKey.value}/catalogs/default/code-lists/inline-refresh-test/refresh")
        assertThat(JsonPath.read<String>(problem, "$.detail")).contains("not refreshed from a source")
        assertThat(problem).doesNotContain("\"details\":")
    }

    @Test
    fun `get missing code list returns 404 problem details`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/missing-list",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = response.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/code-list-not-found")
        assertThat(JsonPath.read<String>(problem, "$.title")).isEqualTo("Code List Not Found")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(404)
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/code-list-not-found")
        assertThat(JsonPath.read<String>(problem, "$.tenantId")).isEqualTo(tenantKey.value)
        assertThat(JsonPath.read<String>(problem, "$.catalogId")).isEqualTo("default")
        assertThat(JsonPath.read<String>(problem, "$.codeListId")).isEqualTo("missing-list")
        assertThat(JsonPath.read<String>(problem, "$.instance"))
            .isEqualTo("/api/tenants/${tenantKey.value}/catalogs/default/code-lists/missing-list")
    }

    @Test
    fun `create + get + delete code list round-trip on default catalog`() {
        val (tenantKey, key) = seedTenantAndKey()

        // Create
        val createBody = """
            {
              "slug": "priorities",
              "displayName": "Priorities",
              "sourceType": "INLINE",
              "entries": [
                { "code": "low", "label": "Low", "sortOrder": 1 },
                { "code": "high", "label": "High", "sortOrder": 2 }
              ]
            }
        """.trimIndent()
        val createResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists",
            HttpMethod.POST,
            HttpEntity(createBody, baseHeaders(key)),
            String::class.java,
        )
        assertThat(createResp.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(JsonPath.read<String>(createResp.body!!, "$.slug")).isEqualTo("priorities")
        assertThat(JsonPath.read<Boolean>(createResp.body!!, "$.readOnly")).isFalse

        // Get
        val getResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/priorities",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(getResp.statusCode).isEqualTo(HttpStatus.OK)

        // List entries
        val entriesResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/priorities/entries",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(entriesResp.statusCode).isEqualTo(HttpStatus.OK)
        val codes: List<String> = JsonPath.read(entriesResp.body!!, "$.items[*].code")
        assertThat(codes).containsExactly("low", "high")

        // Delete
        val deleteResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/priorities",
            HttpMethod.DELETE,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(deleteResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `update code list entry hidden toggles list visibility`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "visibility-${UUID.randomUUID().toString().take(8)}"
        val createBody = """
            {
              "slug": "$slug",
              "displayName": "Visibility",
              "sourceType": "INLINE",
              "entries": [
                { "code": "shown", "label": "Shown", "sortOrder": 1 },
                { "code": "legacy", "label": "Legacy", "sortOrder": 2 }
              ]
            }
        """.trimIndent()
        val createResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists",
            HttpMethod.POST,
            HttpEntity(createBody, baseHeaders(key)),
            String::class.java,
        )
        assertThat(createResp.statusCode).isEqualTo(HttpStatus.CREATED)

        val hideResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/$slug/entries/legacy/hidden",
            HttpMethod.PATCH,
            HttpEntity("""{"hidden": true}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(hideResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(hideResp.body!!, "$.code")).isEqualTo("legacy")
        assertThat(JsonPath.read<Boolean>(hideResp.body!!, "$.hidden")).isTrue

        val visibleEntries = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/$slug/entries",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(JsonPath.read<List<String>>(visibleEntries.body!!, "$.items[*].code")).containsExactly("shown")

        val allEntries = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/code-lists/$slug/entries?includeHidden=true",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(JsonPath.read<List<String>>(allEntries.body!!, "$.items[*].code")).containsExactly("shown", "legacy")
    }

    @Test
    fun `attribute response carries catalog + readOnly + codeListBinding`() {
        val (tenantKey, key) = seedTenantAndKey()
        // `system.locale` is bound to `system/bcp-47` and lives in the SUBSCRIBED
        // catalog — exercises every new field on AttributeDto in one call.
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/attributes/locale",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.key")).isEqualTo("locale")
        assertThat(JsonPath.read<String>(body, "$.catalog")).isEqualTo("system")
        assertThat(JsonPath.read<String>(body, "$.catalogType")).isEqualTo("SUBSCRIBED")
        assertThat(JsonPath.read<Boolean>(body, "$.readOnly")).isTrue
        assertThat(JsonPath.read<String>(body, "$.codeListBinding.catalog")).isEqualTo("system")
        assertThat(JsonPath.read<String>(body, "$.codeListBinding.slug")).isEqualTo("bcp-47")
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.4.0-SNAPSHOT codelist-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("cl-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "CodeList Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "cl-it").execute()
        tenantKey to created.plaintextKey
    }
}
