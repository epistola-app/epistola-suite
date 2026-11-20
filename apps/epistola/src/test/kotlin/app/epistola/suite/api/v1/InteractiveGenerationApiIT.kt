// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.applications.commands.SetApplicationReturnUrls
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.commands.RedeemFormLink
import app.epistola.suite.documents.commands.SubmitInteractiveForm
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
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
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Interactive generation over HTTP (contract ADR 0011, conformance scenarios 400–406), through the
 * real API-key filter: the echo, the waiting job and its link, the link handed out again, `data`
 * and `editedFields` on items and collect lines, a `cancelled` line, the refusals, and `data` sent
 * back to render again.
 */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Documents are really rendered: the poller exists so the test can drain the tenant, and its
    // scheduled tick is pushed out of reach so it never races that drain.
    properties = [
        "epistola.demo.enabled=false",
        "epistola.generation.polling.enabled=true",
        "epistola.generation.polling.interval-ms=3600000",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class InteractiveGenerationApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val objectMapper = ObjectMapper()

    private data class Caller(val tenant: TenantKey, val key: String)

    @Test
    fun `missing data makes a waiting job, and the job status hands out a fresh link to it`() {
        val caller = caller()

        val accepted = generate(caller, """{"name": "Jane"}""", """{"trigger": "incomplete", "title": "Letter, case Z-1"}""")

        assertThat(accepted.statusCode).describedAs(accepted.body).isEqualTo(HttpStatus.ACCEPTED)
        val body = accepted.body!!
        assertThat(JsonPath.read<String>(body, "$.status")).isEqualTo("awaiting-input")
        assertThat(JsonPath.read<String>(body, "$.interactive.trigger")).isEqualTo("incomplete")
        val url = JsonPath.read<String>(body, "$.interactive.url")
        assertThat(url).startsWith("https://suite.test/forms/${JsonPath.read<String>(body, "$.requestId")}?authCode=")
        assertThat(JsonPath.read<String?>(body, "$.interactive.expiresAt")).isNotNull()
        assertThat(JsonPath.read<List<String>>(body, "$.interactive.missingFields[*].path")).contains("/motivation")
        assertThat(JsonPath.read<Any?>(body, "$.cancelReason")).isNull()

        val requestId = JsonPath.read<String>(body, "$.requestId")
        val status = exchange(HttpMethod.GET, "/api/tenants/${caller.tenant.value}/documents/jobs/$requestId", caller.key).body!!
        assertThat(JsonPath.read<String>(status, "$.status")).isEqualTo("awaiting-input")
        // A fresh, short-lived link on every call (epistola-contract#134); the listing hands out none.
        assertThat(JsonPath.read<String>(status, "$.interactive.url")).startsWith("https://suite.test/forms/$requestId?authCode=").isNotEqualTo(url)
        val listed = exchange(HttpMethod.GET, "/api/tenants/${caller.tenant.value}/documents/jobs", caller.key).body!!
        assertThat(JsonPath.read<List<Any?>>(listed, "$.items[?(@.requestId == '$requestId')].interactive.url")).containsExactly(null)

        val items = exchange(HttpMethod.GET, "/api/tenants/${caller.tenant.value}/documents/jobs/$requestId/items", caller.key).body!!
        assertThat(JsonPath.read<String>(items, "$.items[0].status")).isEqualTo("awaiting-input")
        assertThat(JsonPath.read<Any?>(items, "$.items[0].data")).`as`("no input before the person confirms it").isNull()
        assertThat(JsonPath.read<Any?>(items, "$.items[0].editedFields")).isNull()
    }

    @Test
    fun `complete data renders at once and the echo says the form was honoured`() {
        val caller = caller()

        val body = generate(caller, """{"name": "Jane", "motivation": "Because."}""", """{"trigger": "incomplete"}""").body!!

        assertThat(JsonPath.read<String>(body, "$.status")).isEqualTo("pending")
        assertThat(JsonPath.read<Any?>(body, "$.interactive.url")).isNull()
        assertThat(JsonPath.read<List<Any>>(body, "$.interactive.missingFields")).isEmpty()
    }

    @Test
    fun `without the feature the request is answered as a server that predates it would`() {
        val caller = caller(featureOn = false)

        val body = generate(caller, "{}", """{"trigger": "always"}""").body!!

        assertThat(JsonPath.read<String>(body, "$.status")).isEqualTo("pending")
        assertThat(JsonPath.read<Any?>(body, "$.interactive")).isNull()
    }

    @Test
    fun `after submission the item and the collect line carry the data and what was edited, and it renders again from it`() {
        val caller = caller()
        val accepted = generate(caller, """{"name": "Jane"}""", """{"trigger": "incomplete"}""").body!!
        val requestId = JsonPath.read<String>(accepted, "$.requestId")
        val code = JsonPath.read<String>(accepted, "$.interactive.url").substringAfter("authCode=")
        val submitted = """{"name": "Jane Doe", "motivation": "Because.", "nested": {"note": null}}"""

        withMediator {
            val id = GenerationRequestKey.of(UUID.fromString(requestId))
            val session = requireNotNull(RedeemFormLink(id, code).execute())
            SubmitInteractiveForm(id, session.token, json(submitted)).execute()
        }
        assertThat(drainGenerationJobs(caller.tenant)).isEqualTo(1)

        val items = exchange(HttpMethod.GET, "/api/tenants/${caller.tenant.value}/documents/jobs/$requestId/items", caller.key).body!!
        assertThat(JsonPath.read<String>(items, "$.items[0].status")).isEqualTo("completed")
        assertThat(JsonPath.read<Map<String, Any?>>(items, "$.items[0].data")).containsEntry("motivation", "Because.")
        assertThat(JsonPath.read<List<String>>(items, "$.items[0].editedFields")).containsExactly("/motivation", "/name", "/nested")

        val line = collect(caller).single { JsonPath.read<String>(it, "$.requestId") == requestId }
        assertThat(JsonPath.read<String>(line, "$.status")).isEqualTo("completed")
        assertThat(json(objectMapper.writeValueAsString(JsonPath.read<Map<String, Any?>>(line, "$.data")))).isEqualTo(json(submitted))
        assertThat(JsonPath.read<List<String>>(line, "$.editedFields")).containsExactly("/motivation", "/name", "/nested")

        // Rendering again: the item's data goes back unchanged, without a form.
        val itemData = objectMapper.writeValueAsString(JsonPath.read<Map<String, Any?>>(items, "$.items[0].data"))
        val again = generate(caller, itemData, interactive = null).body!!
        assertThat(JsonPath.read<String>(again, "$.status")).isEqualTo("pending")
    }

    @Test
    fun `cancelling a waiting job records the caller and the collect stream says so`() {
        val caller = caller()
        val requestId = JsonPath.read<String>(generate(caller, "{}", """{"trigger": "always"}""").body!!, "$.requestId")

        val cancel = exchange(HttpMethod.DELETE, "/api/tenants/${caller.tenant.value}/documents/jobs/$requestId", caller.key)
        assertThat(cancel.statusCode).describedAs(cancel.body).isEqualTo(HttpStatus.NO_CONTENT)

        val status = exchange(HttpMethod.GET, "/api/tenants/${caller.tenant.value}/documents/jobs/$requestId", caller.key).body!!
        assertThat(JsonPath.read<String>(status, "$.status")).isEqualTo("cancelled")
        assertThat(JsonPath.read<String>(status, "$.cancelReason")).isEqualTo("caller")
        assertThat(JsonPath.read<Any?>(status, "$.interactive.url")).isNull()

        val line = collect(caller).single { JsonPath.read<String>(it, "$.requestId") == requestId }
        assertThat(JsonPath.read<String>(line, "$.status")).isEqualTo("cancelled")
        assertThat(JsonPath.read<String>(line, "$.cancelReason")).isEqualTo("caller")
        assertThat(JsonPath.read<Any?>(line, "$.data")).isNull()
    }

    /**
     * Disabled until the contract fixes `returnUrl`'s pattern: 2.1.0 declares `^https?://`, a prefix
     * in JSON Schema, which the generated Bean Validation `@Pattern` must match in full, so every
     * real URL is refused with `pattern` before Suite sees it.
     */
    @Disabled("contract 2.1.0: returnUrl's pattern ^https?:// refuses every URL under Bean Validation's full match")
    @Test
    fun `a return URL on the application's origin is accepted and one off it is refused`() {
        val caller = caller()

        val onOrigin = generate(caller, "{}", """{"trigger": "always", "returnUrl": "https://cases.example.org/z/1"}""")
        assertThat(onOrigin.statusCode).describedAs(onOrigin.body).isEqualTo(HttpStatus.ACCEPTED)

        val offOrigin = generate(caller, "{}", """{"trigger": "always", "returnUrl": "https://elsewhere.example.org/x"}""")
        assertThat(offOrigin.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(JsonPath.read<String>(offOrigin.body!!, "$.errors[0].field")).isEqualTo("interactive.returnUrl")
        assertThat(JsonPath.read<String>(offOrigin.body!!, "$.errors[0].code")).isEqualTo("url-not-allowed")
    }

    @Test
    fun `an expiry above the installation's maximum is refused`() {
        val caller = caller()

        val tooLong = generate(caller, "{}", """{"trigger": "always", "expiresInMinutes": 20000}""")
        assertThat(tooLong.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(JsonPath.read<String>(tooLong.body!!, "$.errors[0].field")).isEqualTo("interactive.expiresInMinutes")
        assertThat(JsonPath.read<String>(tooLong.body!!, "$.errors[0].code")).isEqualTo("maximum")
    }

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    private fun generate(caller: Caller, data: String, interactive: String?) = run {
        val namespace = withMediator { CatalogApiPaths.namespace(caller.tenant) }
        val interactiveMember = interactive?.let { ""","interactive":$it""" } ?: ""
        val body = """{"namespace":"$namespace","catalogKey":"default","templateKey":"letter","variantKey":"${VariantKey.INITIAL.value}","data":$data$interactiveMember}"""
        exchange(HttpMethod.POST, "/api/tenants/${caller.tenant.value}/documents/generate", caller.key, body)
    }

    /** The result lines of one collect poll. */
    private fun collect(caller: Caller): List<String> {
        val response = exchange(HttpMethod.POST, "/api/tenants/${caller.tenant.value}/generation/collect", caller.key, "{}") {
            set(HttpHeaders.ACCEPT_ENCODING, "identity")
        }
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        return response.body!!.trimEnd('\n').split("\n").filterNot { "\"_meta\"" in it }
    }

    /**
     * A tenant with a released template whose contract requires `name` and `motivation`, and a key of an
     * application whose frontend is on https://cases.example.org.
     */
    private fun caller(featureOn: Boolean = true): Caller = withMediator {
        val tenant = TenantKey.of("interactive-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenant, "Interactive Tenant")
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("letter"), CatalogId.default(TenantId(tenant))))
        CreateDocumentTemplate(variant.templateId, "Letter", catalogVersion = null).execute()
        UpdateDataContract(
            templateId = variant.templateId,
            dataModel = json("""{"type": "object", "properties": {"name": {"type": "string"}, "motivation": {"type": "string"}}, "required": ["name", "motivation"]}"""),
            dataExamples = listOf(DataExample("example-1", "Example 1", json("""{"name": "Ada", "motivation": "An example."}"""))),
            catalogVersion = null,
        ).execute()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter"), catalogVersion = null).execute()
        mediator.publishAndRelease(variant)
        mediator.releaseNext(variant.templateId.catalogId)
        if (featureOn) SaveFeatureToggle(tenant, KnownFeatures.INTERACTIVE_GENERATION, true).execute()
        val application = CreateApplication(tenant, "Case system", roles = setOf(TenantRole.DOCUMENT_GENERATOR)).execute()
        SetApplicationReturnUrls(tenant, application.id, listOf("https://cases.example.org")).execute()
        Caller(tenant, CreateApiKey(tenant, "case", applicationId = application.id).execute().plaintextKey)
    }

    private fun exchange(method: HttpMethod, path: String, key: String, body: String? = null, extra: HttpHeaders.() -> Unit = {}) = restTemplate.exchange(path, method, HttpEntity(body, headers(key).apply(extra)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"), MediaType.parseMediaType("application/vnd.epistola.v2+ndjson"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.1.0 interactive-it")
        set("X-EP-Node-Id", "node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }
}
