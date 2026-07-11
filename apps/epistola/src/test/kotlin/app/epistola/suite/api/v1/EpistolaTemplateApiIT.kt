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
    fun `update template with dataModel publishes it and subsequent get returns the dataModel`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "dm-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Has Model"}""", baseHeaders(key)),
            String::class.java,
        )

        val dataModel = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
        val patch = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": $dataModel}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(patch.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(patch.body!!, "$.dataModel.type")).isEqualTo("object")

        val get = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(get.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(get.body!!, "$.dataModel.type")).isEqualTo("object")
        assertThat(JsonPath.read<String>(get.body!!, "$.dataModel.properties.name.type")).isEqualTo("string")
    }

    @Test
    fun `update template with data examples incompatible with dataModel returns 422`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "inv-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Invalid Examples"}""", baseHeaders(key)),
            String::class.java,
        )

        // Schema requires "name"; the example omits it → incompatible.
        val dataModel = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
        val examples = """[{"id":"ex1","name":"Bad","data":{"unexpected":"value"}}]"""
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": $dataModel, "dataExamples": $examples}""", baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val problem = response.body!!
        assertThat(JsonPath.read<String>(problem, "$.type"))
            .isEqualTo("https://epistola.app/errors/data-model-validation-error")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(422)
        assertThat(JsonPath.read<Any>(problem, "$.validationErrors")).isNotNull
    }

    @Test
    fun `forceUpdate does not bypass data example validation`() {
        // A published contract may never carry examples it would reject, so even
        // forceUpdate=true must not let incompatible examples through.
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "force-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Forced"}""", baseHeaders(key)),
            String::class.java,
        )

        val dataModel = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
        val examples = """[{"id":"ex1","name":"Bad","data":{"unexpected":"value"}}]"""
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": $dataModel, "dataExamples": $examples, "forceUpdate": true}""", baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(422)
    }

    @Test
    fun `update template with a breaking schema change is rejected without forceUpdate`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "brk-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Breaking"}""", baseHeaders(key)),
            String::class.java,
        )
        // Publish v1 with name:string.
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"string"}}}}""", baseHeaders(key)),
            String::class.java,
        )

        // Change name:string -> name:integer is a breaking type change.
        val breaking = """{"dataModel": {"type":"object","properties":{"name":{"type":"integer"}}}}"""
        val rejected = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity(breaking, baseHeaders(key)),
            String::class.java,
        )
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(rejected.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        assertThat(JsonPath.read<String>(rejected.body!!, "$.type"))
            .isEqualTo("https://epistola.app/errors/contract-publish-conflict")
        assertThat(JsonPath.read<List<Any>>(rejected.body!!, "$.breakingChanges")).isNotEmpty

        // The previously published model is unchanged.
        val get = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(JsonPath.read<String>(get.body!!, "$.dataModel.properties.name.type")).isEqualTo("string")
    }

    @Test
    fun `a rejected breaking change includes recent-usage impact in the 409`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "usage-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Usage 409"}""", baseHeaders(key)),
            String::class.java,
        )
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"string"}}}}""", baseHeaders(key)),
            String::class.java,
        )

        val breaking = """{"dataModel": {"type":"object","properties":{"name":{"type":"integer"}}}}"""
        val rejected = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity(breaking, baseHeaders(key)),
            String::class.java,
        )

        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)
        // The recent-usage impact rides along on the conflict body (#280). No documents were
        // generated (polling is disabled here), so nothing regresses, but the check was applicable.
        assertThat(JsonPath.read<Boolean>(rejected.body!!, "$.recentUsage.applicable")).isTrue
        assertThat(JsonPath.read<Int>(rejected.body!!, "$.recentUsage.sampledDocuments")).isEqualTo(0)
        assertThat(JsonPath.read<List<Any>>(rejected.body!!, "$.recentUsage.fields")).isEmpty()
    }

    @Test
    fun `check recent usage impact returns 200 with the impact shape`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "impact-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Impact"}""", baseHeaders(key)),
            String::class.java,
        )
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"string"}}}}""", baseHeaders(key)),
            String::class.java,
        )

        // A breaking candidate (taxId now required) — applicable, but no recent usage to replay.
        val candidate = """{"dataModel":{"type":"object","properties":{"name":{"type":"string"},"taxId":{"type":"string"}},"required":["name","taxId"]}}"""
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug/contract/usage-impact",
            HttpMethod.POST,
            HttpEntity(candidate, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Boolean>(response.body!!, "$.applicable")).isTrue
        assertThat(JsonPath.read<Int>(response.body!!, "$.sampledDocuments")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(response.body!!, "$.failingDocuments")).isEqualTo(0)
        assertThat(JsonPath.read<List<Any>>(response.body!!, "$.fields")).isEmpty()
    }

    @Test
    fun `check recent usage impact is not applicable for a compatible candidate`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "compat-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Compat"}""", baseHeaders(key)),
            String::class.java,
        )
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}""", baseHeaders(key)),
            String::class.java,
        )

        // Adding an optional field is backwards compatible → nothing could regress.
        val candidate = """{"dataModel":{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}},"required":["name"]}}"""
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug/contract/usage-impact",
            HttpMethod.POST,
            HttpEntity(candidate, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Boolean>(response.body!!, "$.applicable")).isFalse
    }

    @Test
    fun `update template with a breaking schema change succeeds with forceUpdate`() {
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "brkf-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Breaking Forced"}""", baseHeaders(key)),
            String::class.java,
        )
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"string"}}}}""", baseHeaders(key)),
            String::class.java,
        )

        val breaking = """{"dataModel": {"type":"object","properties":{"name":{"type":"integer"}}}, "forceUpdate": true}"""
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity(breaking, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(response.body!!, "$.dataModel.properties.name.type")).isEqualTo("integer")
    }

    @Test
    fun `a rejected breaking change leaves no draft, so data validation still uses the published model`() {
        // Regression: the create -> update -> publish steps each commit separately, so a
        // publish rejected as breaking must not leave a dangling draft holding the rejected
        // schema. validate-data reads the draft-preferred contract, so it would observe such
        // a leak: data valid under the published model would be rejected by the leaked draft.
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "leak-${randomSuffix()}"

        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "No Leak"}""", baseHeaders(key)),
            String::class.java,
        )
        // Publish v1 with name:string.
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"string"}}}}""", baseHeaders(key)),
            String::class.java,
        )

        // Breaking change name:string -> name:integer without forceUpdate is rejected (409).
        val rejected = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug",
            HttpMethod.PATCH,
            HttpEntity("""{"dataModel": {"type":"object","properties":{"name":{"type":"integer"}}}}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)

        // A string name is valid under the published model. If the rejected draft (name:integer)
        // had leaked, validate-data would mark it invalid.
        val validate = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug/validate",
            HttpMethod.POST,
            HttpEntity("""{"data": {"name": "hello"}}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(validate.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Boolean>(validate.body!!, "$.valid")).isTrue
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

    @Test
    fun `upsert and get variant draft round-trip the templateModel`() {
        // Regression for #662: VersionDto-returning endpoints 500'd because the mapper
        // built the strict templateModel field with valueToTree (an ObjectNode) instead
        // of convertValue. Any version carrying a templateModel triggered a
        // ClassCastException. This exercises upsertVariantDraft + getVariantDraft over HTTP.
        val (tenantKey, key) = seedTenantAndKey()
        val slug = "ver-${randomSuffix()}"

        val create = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "$slug", "name": "Version Model"}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(create.statusCode).isEqualTo(HttpStatus.CREATED)
        val variantId = JsonPath.read<String>(create.body!!, "$.variants[0].id")

        val templateModel = """
            {
              "modelVersion": 1,
              "root": "root",
              "nodes": {
                "root": {"id": "root", "type": "root", "slots": ["slot-root"]},
                "text1": {"id": "text1", "type": "text", "slots": [], "props": {"content": "Hello #662"}}
              },
              "slots": {
                "slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["text1"]}
              },
              "themeRef": {"type": "inherit"}
            }
        """.trimIndent()
        val draftUrl =
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/$slug/variants/$variantId/draft"

        val upsert = restTemplate.exchange(
            draftUrl,
            HttpMethod.PUT,
            HttpEntity("""{"templateModel": $templateModel}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(upsert.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(upsert.body!!, "$.templateModel.root")).isEqualTo("root")
        assertThat(JsonPath.read<String>(upsert.body!!, "$.templateModel.nodes.text1.props.content"))
            .isEqualTo("Hello #662")

        val get = restTemplate.exchange(
            draftUrl,
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(get.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Int>(get.body!!, "$.templateModel.modelVersion")).isEqualTo(1)
        assertThat(JsonPath.read<String>(get.body!!, "$.templateModel.root")).isEqualTo("root")
        assertThat(JsonPath.read<String>(get.body!!, "$.templateModel.nodes.text1.props.content"))
            .isEqualTo("Hello #662")
    }

    @Test
    fun `list templates paginates with a page envelope and accurate totals`() {
        val (tenantKey, key) = seedTenantAndKey()
        // Seed three templates so a size=2 page splits into two pages.
        repeat(3) { i ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "page-$i-${randomSuffix()}", "name": "Page $i"}""", baseHeaders(key)),
                String::class.java,
            )
        }

        val first = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?page=0&size=2",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        val firstJson = first.body!!
        assertThat(JsonPath.read<List<Any>>(firstJson, "$.items")).hasSize(2)
        assertThat(JsonPath.read<Int>(firstJson, "$.page.number")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(firstJson, "$.page.size")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(firstJson, "$.page.totalElements")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(firstJson, "$.page.totalPages")).isEqualTo(2)

        val second = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?page=1&size=2",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        val secondJson = second.body!!
        assertThat(JsonPath.read<List<Any>>(secondJson, "$.items")).hasSize(1)
        assertThat(JsonPath.read<Int>(secondJson, "$.page.number")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(secondJson, "$.page.totalElements")).isEqualTo(3)
    }

    @Test
    fun `list templates sorts by name ascending and descending`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        // Create in an order that matches neither ascending nor descending name order,
        // so the assertions prove the sort is applied rather than incidental.
        listOf("Cherry" to "cherry", "Apple" to "apple", "Banana" to "banana").forEach { (name, id) ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "$id-$suffix", "name": "$name"}""", baseHeaders(key)),
                String::class.java,
            )
        }

        val asc = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=name&direction=asc",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(asc.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(asc.body!!, "$.items[*].name"))
            .containsExactly("Apple", "Banana", "Cherry")

        val desc = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=name&direction=desc",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(desc.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(desc.body!!, "$.items[*].name"))
            .containsExactly("Cherry", "Banana", "Apple")
    }

    @Test
    fun `list templates name sort is case-insensitive`() {
        // Name sort uses LOWER(name), so it stays alphabetical regardless of case and
        // matches the case-insensitive search filter. Caveat: the CI Postgres collation
        // is already case-insensitive-ish, so this can't fully falsify a regression that
        // drops LOWER() — it pins intent and would catch it on a C-collation cluster.
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        listOf("banana" to "banana", "Apple" to "apple", "cherry" to "cherry").forEach { (name, id) ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "$id-$suffix", "name": "$name"}""", baseHeaders(key)),
                String::class.java,
            )
        }

        val asc = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=name&direction=asc",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(asc.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(asc.body!!, "$.items[*].name"))
            .containsExactly("Apple", "banana", "cherry")
    }

    @Test
    fun `list templates sort field is matched case-insensitively, like direction`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        listOf("Cherry" to "cherry", "Apple" to "apple", "Banana" to "banana").forEach { (name, id) ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "$id-$suffix", "name": "$name"}""", baseHeaders(key)),
                String::class.java,
            )
        }

        // Mixed-case 'Name'/'ASC' must be honored, not silently dropped to the default order —
        // the sort field resolves case-insensitively, matching the direction parameter.
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=Name&direction=ASC",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(response.body!!, "$.items[*].name"))
            .containsExactly("Apple", "Banana", "Cherry")
    }

    @Test
    fun `list templates rejects an unrecognized direction with 400`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "only-$suffix", "name": "Only"}""", baseHeaders(key)),
            String::class.java,
        )

        // direction is validated the same way as sort: an unrecognized non-null value fails loudly
        // (400) rather than being silently reinterpreted as the contract default. The problem body
        // enumerates the supported directions.
        val resp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=name&direction=sideways",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val problem = resp.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/unsupported-sort-direction")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(problem, "$.value")).isEqualTo("sideways")
        assertThat(JsonPath.read<List<String>>(problem, "$.supportedValues"))
            .containsExactly("asc", "desc")
    }

    @Test
    fun `list templates sorts by created and updated as distinct columns`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        // Creation order (== created_at ascending) is deliberately different from both
        // name order and id order, so a correct created_at sort matches none of the other
        // columns — proving it isn't silently using name or the dt.id tiebreaker.
        //   creation:  mango,  apple,  zebra   (ids c-,  a-,  b-)
        //   name asc:  apple,  mango,  zebra
        //   id asc:    apple,  zebra,  mango
        listOf(
            "Mango" to "c-mango",
            "Apple" to "a-apple",
            "Zebra" to "b-zebra",
        ).forEach { (name, id) ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "$id-$suffix", "name": "$name"}""", baseHeaders(key)),
                String::class.java,
            )
        }
        // Bump the second-created row so updated order diverges from created order too.
        val patch = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/a-apple-$suffix",
            HttpMethod.PATCH,
            HttpEntity("""{"name": "Apple Updated"}""", baseHeaders(key)),
            String::class.java,
        )
        assertThat(patch.statusCode).isEqualTo(HttpStatus.OK)

        val byCreated = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=createdAt&direction=asc",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        // Pure creation order — differs from both name-asc and id-asc above.
        assertThat(JsonPath.read<List<String>>(byCreated.body!!, "$.items[*].id"))
            .containsExactly("c-mango-$suffix", "a-apple-$suffix", "b-zebra-$suffix")

        val byUpdated = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=lastModified&direction=asc",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        // 'apple' was updated most recently, so ascending updated_at puts it last —
        // an order created_at could never produce, proving the two columns are distinct.
        assertThat(JsonPath.read<List<String>>(byUpdated.body!!, "$.items[*].id"))
            .containsExactly("c-mango-$suffix", "b-zebra-$suffix", "a-apple-$suffix")
    }

    @Test
    fun `list templates keeps a stable order across pages when the sort key ties`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        // Five templates sharing one name: the sort key ties on every row, so only the
        // dt.id tiebreaker keeps paging from repeating or dropping rows across requests.
        val ids = (0..4).map { "same-$it-$suffix" }
        ids.forEach { id ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "$id", "name": "Same Name"}""", baseHeaders(key)),
                String::class.java,
            )
        }

        val paged = (0..2).flatMap { page ->
            val resp = restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=name&direction=asc&page=$page&size=2",
                HttpMethod.GET,
                HttpEntity<String>(null, baseHeaders(key)),
                String::class.java,
            )
            JsonPath.read<List<String>>(resp.body!!, "$.items[*].id")
        }

        // Every id appears exactly once, in the id-ascending order the tiebreaker guarantees.
        // Caveat: this is a stronger assertion than CI can fully falsify — on a 5-row table
        // Postgres seq-scans in a stable heap order, so dropping the `dt.id` tiebreaker may
        // not actually reorder these pages here. The test documents and pins the intended
        // contract; the guarantee itself rests on the explicit ORDER BY tiebreaker.
        assertThat(paged).containsExactlyElementsOf(ids)
    }

    @Test
    fun `list templates with no sort parameters uses the default updated-descending order`() {
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        listOf("first", "second", "third").forEach { id ->
            restTemplate.exchange(
                "/api/tenants/${tenantKey.value}/catalogs/default/templates",
                HttpMethod.POST,
                HttpEntity("""{"id": "$id-$suffix", "name": "Template $id"}""", baseHeaders(key)),
                String::class.java,
            )
        }
        // Bump 'first' so newest-updated order differs from insertion order — otherwise
        // the assertion couldn't tell a real sort from incidental ordering.
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates/first-$suffix",
            HttpMethod.PATCH,
            HttpEntity("""{"name": "First Updated"}""", baseHeaders(key)),
            String::class.java,
        )

        // No sort/direction query params at all: the contract defaults (updated, desc) apply.
        val resp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        // Newest-updated first: 'first' (just patched), then third, then second.
        assertThat(JsonPath.read<List<String>>(resp.body!!, "$.items[*].id"))
            .containsExactly("first-$suffix", "third-$suffix", "second-$suffix")
    }

    @Test
    fun `list templates rejects an unrecognized sort key with 400`() {
        // The contract declares sort as a free-form string with no validation of its own, so the
        // server validates it: a non-null value that isn't a supported key is rejected with a 400
        // problem+json whose body enumerates the supported keys the contract no longer advertises.
        val (tenantKey, key) = seedTenantAndKey()
        val suffix = randomSuffix()
        restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates",
            HttpMethod.POST,
            HttpEntity("""{"id": "only-$suffix", "name": "Only"}""", baseHeaders(key)),
            String::class.java,
        )

        val resp = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/templates?sort=title",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val problem = resp.body!!
        assertThat(JsonPath.read<String>(problem, "$.type")).isEqualTo("https://epistola.app/errors/unsupported-sort")
        assertThat(JsonPath.read<Int>(problem, "$.status")).isEqualTo(400)
        assertThat(JsonPath.read<String>(problem, "$.value")).isEqualTo("title")
        assertThat(JsonPath.read<List<String>>(problem, "$.supportedValues"))
            .containsExactly("name", "createdAt", "lastModified")
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
