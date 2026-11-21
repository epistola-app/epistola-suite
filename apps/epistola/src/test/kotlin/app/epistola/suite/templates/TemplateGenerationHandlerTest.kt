// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.GenerationJobTokens
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.ControllableDocumentGenerationExecutor
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import app.epistola.suite.testing.TEST_USER_ID_HEADER
import app.epistola.suite.testing.TestPrincipalUser
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * "Generate document" on the template page over real HTTP: the action and its dialog, the job it
 * starts with a form and without, the job's page that follows it to its PDF, and above all that the
 * job routes answer only the person the token was issued to — not another person with the same roles,
 * not a changed or expired token, not a form session, not another tenant.
 */
class TemplateGenerationHandlerTest : BaseIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var executor: ControllableDocumentGenerationExecutor

    @Autowired
    private lateinit var contentStore: DocumentContentStore

    @Autowired
    private lateinit var tokens: GenerationJobTokens

    @Autowired
    private lateinit var formLinks: InteractiveFormLinks

    private val objectMapper = ObjectMapper()

    private val htmxRequest = "HX-Request" to "true"

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @Test
    fun `the page offers generating to whoever may generate, and the dialog asks for a form only with the toggle on`() {
        val tenant = releasedTemplate(formsOn = false)

        assertThat(get(page(tenant)).body()).contains("data-testid=\"generate-document-open\"")
        assertThat(get(page(tenant), TEST_TENANT_ROLES_HEADER to "CONTENT_VIEWER").body()).doesNotContain("generate-document-open")

        val dialog = get("${page(tenant)}/generate", "HX-Request" to "true").body()
        assertThat(dialog).contains("data-testid=\"generate-target\"", "data-testid=\"generate-variant\"", "&quot;name&quot; : &quot;Ada&quot;")
        assertThat(dialog).doesNotContain("generate-trigger")

        withMediator { SaveFeatureToggle(tenant, KnownFeatures.INTERACTIVE_GENERATION, true).execute() }
        assertThat(get("${page(tenant)}/generate", "HX-Request" to "true").body()).contains("data-testid=\"generate-trigger\"")
    }

    @Test
    fun `a template with nothing to generate from says so instead of offering an empty choice`() {
        val tenant = tenantWithTemplate()

        val dialog = get("${page(tenant)}/generate", "HX-Request" to "true").body()

        assertThat(dialog).contains("data-testid=\"generate-nothing\"").doesNotContain("generate-submit")
    }

    @Test
    fun `data that is not a JSON object comes back marked in the dialog`() {
        val tenant = releasedTemplate()

        val response = generate(tenant, data = "[1, 2]")

        assertThat(response.statusCode()).isEqualTo(422)
        assertThat(response.body()).contains("Enter the data as a JSON object")
        assertThat(jobsOf(tenant)).isZero()
    }

    @Test
    fun `without a form the job's page follows the job to its PDF`() {
        val tenant = releasedTemplate()

        val started = generate(tenant)
        assertThat(started.statusCode()).isEqualTo(200)
        val next = started.headers().firstValue("HX-Redirect").orElseThrow()
        assertThat(next).startsWith("${page(tenant)}/jobs/")
        val token = next.substringAfter("/jobs/")

        // Opened directly: the whole page, with what the job renders and the data it renders.
        val opened = get(next)
        assertThat(opened.statusCode()).isEqualTo(200)
        assertThat(opened.body()).contains(
            "Generated document",
            "data-status=\"PENDING\"",
            "hx-trigger=\"every 2s\"",
            "the catalog's latest",
            "&quot;name&quot; : &quot;Jane&quot;",
        )
        // Refreshing itself: the job part alone.
        val refreshed = get(panel(tenant, token), htmxRequest).body()
        assertThat(refreshed).contains("data-status=\"PENDING\"").doesNotContain("page-header")

        val request = withMediator { GetGenerationJob(tenant, verify(token)).query()!!.request }
        val pdf = "%PDF-1.7 test".toByteArray()
        val documentKey = withMediator { executor.complete(request, filename = "letter.pdf", sizeBytes = pdf.size.toLong()) }
        contentStore.put(ContentKey.document(tenant, documentKey), ByteArrayInputStream(pdf), "application/pdf", pdf.size.toLong(), OffsetDateTime.now())

        val done = get(panel(tenant, token), htmxRequest).body()
        assertThat(done).contains("data-status=\"COMPLETED\"", "data-testid=\"generation-job-download\"").doesNotContain("every 2s")
        val download = getBytes("${panel(tenant, token)}/document")
        assertThat(download.statusCode()).isEqualTo(200)
        assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf")
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment", "letter.pdf")
        assertThat(download.body()).isEqualTo(pdf)
    }

    @Test
    fun `with a form the browser goes to it, and cancelling it returns to the job's page`() {
        val tenant = releasedTemplate()

        val started = generate(tenant, trigger = "ALWAYS")
        val formUrl = started.headers().firstValue("HX-Redirect").orElseThrow()
        assertThat(formUrl).startsWith("https://suite.test/forms/")

        val session = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port${formUrl.substringAfter("https://suite.test")}")).GET().build(), HttpResponse.BodyHandlers.ofString())
            .headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
        val formPath = formUrl.substringAfter("https://suite.test").substringBefore('?')
        val formPage = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port$formPath")).header("Cookie", session).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        val guard = Regex("""name="guard" value="([^"]+)"""").find(formPage)!!.groupValues[1]
        val cancelled = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$formPath/cancel"))
                .header("Cookie", session).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("guard=${URLEncoder.encode(guard, Charsets.UTF_8)}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        val back = cancelled.headers().firstValue("Location").orElseThrow()
        assertThat(back).startsWith("https://suite.test${page(tenant)}/jobs/").contains("epistolaRequestStatus=cancelled")
        val token = back.substringAfter("/jobs/").substringBefore('?')
        assertThat(get(panel(tenant, token)).body()).contains("data-status=\"CANCELLED\"", "The form was cancelled").doesNotContain("every 2s", "every 5s")
    }

    @Test
    fun `while the form waits the job's page links to it, keeps checking, and cancels the job`() {
        val tenant = releasedTemplate()
        val token = tokenOf(tenant, generate(tenant, trigger = "ALWAYS"))

        val waiting = get(panel(tenant, token)).body()
        assertThat(waiting).contains("data-status=\"AWAITING_INPUT\"", "data-testid=\"generation-job-open-form\"", "https://suite.test/forms/", "hx-trigger=\"every 5s\"", "Not yet")

        val cancelled = post("${panel(tenant, token)}/cancel", htmxRequest)
        assertThat(cancelled.statusCode()).isEqualTo(200)
        assertThat(cancelled.body()).contains("data-status=\"CANCELLED\"")
    }

    @Test
    fun `nobody but the person the token was issued to reaches the job`() {
        val tenant = releasedTemplate()
        val token = tokenOf(tenant, generate(tenant, trigger = "ALWAYS"))
        val requestId = verify(token)
        val someoneElse = TEST_USER_ID_HEADER to UUID.randomUUID().toString()

        // Another person with the same roles, holding the very same URL.
        assertNotFound(tenant, token, someoneElse)
        // The token changed, expired, or a form session signed by the same key in its place.
        val (payload, signature) = token.split('.')
        assertNotFound(tenant, "$payload.${signature.reversed()}")
        assertNotFound(tenant, EpistolaClock.withClock(Clock.systemUTC()) { tokens.issue(requestId, TestPrincipalUser.ID, OffsetDateTime.now().minusMinutes(1)) })
        assertNotFound(tenant, EpistolaClock.withClock(Clock.systemUTC()) { formLinks.openSession(requestId, OffsetDateTime.now().plusHours(1)).token })
        // A bare request id is not a token.
        assertNotFound(tenant, requestId.value.toString())
        // Another tenant's template page, where this person may generate too.
        val other = releasedTemplate()
        assertNotFound(other, token)

        assertThat(withMediator { GetGenerationJob(tenant, requestId).query()!!.request.status }).`as`("no cancel got through").isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    @Test
    fun `an environment following a draft is offered, and generating through it renders the draft`() {
        val tenant = tenantWithTemplate()
        val staging = EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenant))
        withMediator {
            val catalog = variantOf(tenant).templateId.catalogId
            CreateEnvironment(staging, "Staging").execute()
            DeployCatalogRelease(staging, catalog, GetCatalogDraft(catalog).query()!!.versionId).execute()
        }

        val dialog = get("${page(tenant)}/generate", htmxRequest).body()
        assertThat(dialog).contains("value=\"environment:staging\"", "staging (draft: ", "To generate from a draft, deploy it to a test environment.")

        val started = generate(tenant, target = "environment:staging")
        assertThat(started.statusCode()).describedAs(started.body()).isEqualTo(200)
        val token = started.headers().firstValue("HX-Redirect").orElseThrow().substringAfter("/jobs/")
        assertThat(withMediator { GetGenerationJob(tenant, verify(token)).query()!!.request.draftId }).isNotNull()
        assertThat(get(panel(tenant, token)).body()).contains("The draft <strong>", "<code>staging</code> follows it")
    }

    // ---------------------------------------------------------------------------------------------

    private fun assertNotFound(tenant: TenantKey, token: String, vararg headers: Pair<String, String>) {
        assertThat(get(panel(tenant, token), *headers).statusCode()).`as`("page").isEqualTo(404)
        assertThat(get(panel(tenant, token), htmxRequest, *headers).statusCode()).`as`("refresh").isEqualTo(404)
        assertThat(getBytes("${panel(tenant, token)}/document", *headers).statusCode()).`as`("document").isEqualTo(404)
        assertThat(post("${panel(tenant, token)}/cancel", htmxRequest, *headers).statusCode()).`as`("cancel").isEqualTo(404)
    }

    /** The request [token] names, checked on the clock the server issued it on. */
    private fun verify(token: String): GenerationRequestKey = EpistolaClock.withClock(Clock.systemUTC()) { tokens.verify(token, TestPrincipalUser.ID)!! }

    private fun page(tenant: TenantKey) = "/tenants/${tenant.value}/catalogs/${tenant.value}/default/templates/letter"

    private fun panel(tenant: TenantKey, token: String) = "${page(tenant)}/jobs/$token"

    private fun tokenOf(tenant: TenantKey, started: HttpResponse<String>): String {
        assertThat(started.statusCode()).describedAs(started.body()).isEqualTo(200)
        // With a form the redirect goes to it; the token rides its return URL, which the job keeps.
        val requestId = GenerationRequestKey.of(UUID.fromString(started.headers().firstValue("HX-Redirect").orElseThrow().substringAfter("/forms/").substringBefore('?')))
        return EpistolaClock.withClock(Clock.systemUTC()) { tokens.issue(requestId, TestPrincipalUser.ID, OffsetDateTime.now().plusHours(1)) }
            .also { assertThat(withMediator { GetGenerationJob(tenant, requestId).query() }).isNotNull() }
    }

    private fun jobsOf(tenant: TenantKey): Int = withMediator {
        app.epistola.suite.documents.queries.ListGenerationJobs(tenant).query().size
    }

    private fun generate(tenant: TenantKey, data: String = """{"name": "Jane", "motivation": "Because."}""", trigger: String = "", target: String = "latest"): HttpResponse<String> = post(
        "${page(tenant)}/generate",
        "HX-Request" to "true",
        form = listOf("target" to target, "variant" to VariantKey.INITIAL.value, "data" to data, "trigger" to trigger),
    )

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun getBytes(path: String, vararg headers: Pair<String, String>): HttpResponse<ByteArray> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

    private fun post(path: String, vararg headers: Pair<String, String>, form: List<Pair<String, String>> = emptyList()): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(form.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    /** A tenant whose template `letter` (contract: `name` and `motivation` required) is in no release yet. */
    private fun tenantWithTemplate(): TenantKey = withMediator {
        val tenant = TenantKey.of("templategenerationha-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenant, "Generation Tenant")
        val variant = variantOf(tenant)
        CreateDocumentTemplate(variant.templateId, "Letter", catalogVersion = null).execute()
        UpdateDataContract(
            templateId = variant.templateId,
            dataModel = json("""{"type": "object", "properties": {"name": {"type": "string"}, "motivation": {"type": "string"}}, "required": ["name", "motivation"]}"""),
            dataExamples = listOf(DataExample("example-1", "Example 1", json("""{"name": "Ada", "motivation": "An example."}"""))),
            catalogVersion = null,
        ).execute()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Letter"), catalogVersion = null).execute()
        tenant
    }

    /** [tenantWithTemplate], released, with interactive generation on unless [formsOn] says otherwise. */
    private fun releasedTemplate(formsOn: Boolean = true): TenantKey {
        val tenant = tenantWithTemplate()
        withMediator {
            val variant = variantOf(tenant)
            mediator.publishAndRelease(variant)
            mediator.releaseNext(variant.templateId.catalogId)
            if (formsOn) SaveFeatureToggle(tenant, KnownFeatures.INTERACTIVE_GENERATION, true).execute()
        }
        return tenant
    }

    private fun variantOf(tenant: TenantKey) = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("letter"), CatalogId.default(TenantId(tenant))))
}
