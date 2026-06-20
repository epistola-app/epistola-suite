package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
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
 * HTTP-level coverage of the template REST CRUD surface. Asserts shape,
 * status codes, and basic tenant isolation.
 *
 * Domain logic is covered by mediator-level tests in epistola-core; this IT
 * specifically verifies the REST controller mapping, exception translation,
 * and authentication/authorization through the API layer.
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
class EpistolaTemplateApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `create template returns 201 with full DTO`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "test-${randomSuffix()}"

        val body = """{"id": "$slug", "name": "Test Template"}"""
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity(body, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val json = response.body!!
        assertThat(JsonPath.read<String>(json, "$.id")).isEqualTo(slug)
        assertThat(JsonPath.read<String>(json, "$.name")).isEqualTo("Test Template")
        assertThat(JsonPath.read<String>(json, "$.tenantId")).isEqualTo(tenantKey.value)
        val variants: List<Any> = JsonPath.read(json, "$.variants")
        assertThat(variants).hasSize(1)
        assertThat(JsonPath.read<Boolean>(json, "$.variants[0].isDefault")).isTrue
        assertThat(JsonPath.read<Boolean>(json, "$.variants[0].hasDraft")).isTrue
        assertThat(JsonPath.read<String>(json, "$.createdAt")).isNotBlank
        assertThat(JsonPath.read<String>(json, "$.lastModified")).isNotBlank
    }

    @Test
    fun `create template with an under-privileged key returns 403 naming the missing permission`() {
        val (tenantKey, viewerKey) = seedTenantAndViewerKey()
        val slug = "denied-${randomSuffix()}"

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Should Be Denied"}""", baseHeaders(viewerKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val json = response.body!!
        assertThat(JsonPath.read<String>(json, "$.type")).isEqualTo("https://epistola.app/errors/permission-denied")
        assertThat(JsonPath.read<String>(json, "$.title")).isEqualTo("Permission Denied")
        assertThat(JsonPath.read<Int>(json, "$.status")).isEqualTo(403)
        // The missing permission is named in both the detail string and a machine-readable extension.
        assertThat(JsonPath.read<String>(json, "$.detail")).isEqualTo("Missing required permission: template-edit")
        assertThat(JsonPath.read<String>(json, "$.requiredPermission")).isEqualTo("template-edit")
        assertThat(JsonPath.read<String>(json, "$.tenantId")).isEqualTo(tenantKey.value)
    }

    @Test
    fun `list templates returns created templates`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "list-${randomSuffix()}"

        // Create a template first
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "List Test"}""", baseHeaders(key)),
            String::class.java,
        )

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val json = response.body!!
        val ids: List<String> = JsonPath.read(json, "$.items[*].id")
        assertThat(ids).contains(slug)
        val names: List<String> = JsonPath.read(json, "$.items[*].name")
        assertThat(names).contains("List Test")
    }

    @Test
    fun `get template returns full DTO`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "get-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Get Test"}""", baseHeaders(key)),
            String::class.java,
        )

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val json = response.body!!
        assertThat(JsonPath.read<String>(json, "$.id")).isEqualTo(slug)
        assertThat(JsonPath.read<String>(json, "$.name")).isEqualTo("Get Test")
        assertThat(JsonPath.read<String>(json, "$.tenantId")).isEqualTo(tenantKey.value)
    }

    @Test
    fun `update template returns updated DTO`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "upd-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Original"}""", baseHeaders(key)),
            String::class.java,
        )

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"name": "Updated"}""", baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(response.body!!, "$.name")).isEqualTo("Updated")
    }

    @Test
    fun `delete template returns 204 and subsequent get returns 404`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "del-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Delete Me"}""", baseHeaders(key)),
            String::class.java,
        )

        val deleteResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.DELETE,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(deleteResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val getResp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(getResp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(getResp.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = getResp.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/template-not-found")
        assertThat(JsonPath.read<String>(problem, "$.title")).isEqualTo("Template Not Found")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(404)
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/template-not-found")
        assertThat(JsonPath.read<String>(problem, "$.templateId")).isEqualTo(slug)
        assertThat(JsonPath.read<String>(problem, "$.tenantId")).isEqualTo(tenantKey.value)
        assertThat(JsonPath.read<String>(problem, "$.instance"))
            .isEqualTo("/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug")
    }

    @Test
    fun `get non-existent template returns 404`() {
        val (tenantKey, key) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/non-existent",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = response.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/template-not-found")
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/template-not-found")
        assertThat(JsonPath.read<String>(problem, "$.templateId")).isEqualTo("non-existent")
    }

    @Test
    fun `delete non-existent template returns 404`() {
        val (tenantKey, key) = seedTenantAndKey()

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/non-existent",
            HttpMethod.DELETE,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = response.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/template-not-found")
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/template-not-found")
        assertThat(JsonPath.read<String>(problem, "$.templateId")).isEqualTo("non-existent")
    }

    @Test
    fun `unauthenticated request returns 401`() {
        val (tenantKey, _) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, HttpHeaders()),
            String::class.java,
        )
        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    @Test
    fun `tenant isolation prevents cross-tenant access`() {
        // Create tenant A with a template
        val (tenantA, keyA) = seedTenantAndKey()
        val slug = "iso-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantA.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Tenant A Template"}""", baseHeaders(keyA)),
            String::class.java,
        )

        // Create tenant B (separate tenant)
        val (tenantB, keyB) = seedTenantAndKey()

        // Tenant B should not see tenant A's template
        val listResponse = restTemplate.exchange(
            "/api/tenants/${tenantB.value}/catalogs/default/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(keyB)),
            String::class.java,
        )
        assertThat(listResponse.statusCode).isEqualTo(HttpStatus.OK)
        val ids: List<String> = JsonPath.read(listResponse.body!!, "$.items[*].id")
        assertThat(ids).doesNotContain(slug)

        // Tenant B should not be able to get tenant A's template (using B's key on A's URL)
        val getResponse = restTemplate.exchange(
            "/api/tenants/${tenantA.value}/catalogs/default/templates/$slug",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(keyB)),
            String::class.java,
        )
        assertThat(getResponse.statusCode).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN)

        // Tenant B should not be able to access via its own tenant path
        val crossGet = restTemplate.exchange(
            "/api/tenants/${tenantB.value}/catalogs/default/templates/$slug",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(keyB)),
            String::class.java,
        )
        assertThat(crossGet.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `list templates returns empty items for tenant with no templates`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val items: List<*> = JsonPath.read(response.body!!, "$.items")
        assertThat(items).isEmpty()
    }

    @Test
    fun `list templates with search query filters results`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()

        // Create two templates with distinct names
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "alpha-$suffix", "name": "Alpha Template"}""", baseHeaders(key)),
            String::class.java,
        )
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "beta-$suffix", "name": "Beta Template"}""", baseHeaders(key)),
            String::class.java,
        )

        // Search for "Beta" should only return beta template
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?q=Beta",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val ids: List<String> = JsonPath.read(response.body!!, "$.items[*].id")
        assertThat(ids).containsExactly("beta-$suffix")
    }

    @Test
    fun `list templates is scoped to the requested catalog`() {
        val (tenantKey, key) = seedTenantAndKey()
        val secondCatalog = "second-${randomSuffix()}"
        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = CatalogKey.of(secondCatalog), name = "Second").execute()
        }
        val defaultSlug = "default-${randomSuffix()}"
        val secondSlug = "second-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$defaultSlug", "name": "Default Catalog Template"}""", baseHeaders(key)),
            String::class.java,
        )
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/$secondCatalog/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$secondSlug", "name": "Second Catalog Template"}""", baseHeaders(key)),
            String::class.java,
        )

        val defaultList = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(defaultList.statusCode).isEqualTo(HttpStatus.OK)
        val defaultIds: List<String> = JsonPath.read(defaultList.body!!, "$.items[*].id")
        assertThat(defaultIds).contains(defaultSlug).doesNotContain(secondSlug)

        val secondList = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/$secondCatalog/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(secondList.statusCode).isEqualTo(HttpStatus.OK)
        val secondIds: List<String> = JsonPath.read(secondList.body!!, "$.items[*].id")
        assertThat(secondIds).contains(secondSlug).doesNotContain(defaultSlug)
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.5.0 template-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("tmpl-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Template Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "tmpl-it").execute()
        tenantKey to created.plaintextKey
    }

    /** Tenant + a viewer-only key (can read but not create/edit templates). */
    private fun seedTenantAndViewerKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("tmpl-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Template Tenant").execute()
        val created = CreateApiKey(
            tenantId = tenantKey,
            name = "tmpl-it-viewer",
            roles = setOf(TenantRole.CONTENT_VIEWER),
        ).execute()
        tenantKey to created.plaintextKey
    }

    private fun randomSuffix(): String = UUID.randomUUID().toString().take(8)
}
