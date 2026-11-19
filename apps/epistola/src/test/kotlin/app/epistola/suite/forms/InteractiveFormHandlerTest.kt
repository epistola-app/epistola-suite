// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.forms

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.util.UUID

/**
 * The public form page (contract ADR 0011, links per epistola-contract#134) over real HTTP, with
 * redirects not followed: redeeming a link into a session cookie and a clean URL, the neutral page for
 * everything that is not a live session, the guard on every post, submit, cancel and preview, and the
 * headers a page reachable without signing in must send.
 */
class InteractiveFormHandlerTest : BaseIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private val objectMapper = ObjectMapper()

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    @Test
    fun `opening a link sets a session cookie for this form only and drops the code from the address bar`() {
        val request = waitingRequest()

        val opened = get(request.interactive!!.url!!.toLocal())

        assertThat(opened.statusCode()).isEqualTo(303)
        assertThat(opened.headers().firstValue("Location")).hasValue("/forms/${request.id.value}")
        val cookie = opened.headers().firstValue("Set-Cookie").orElseThrow()
        assertThat(cookie).startsWith("epistola_form=").contains("Path=/forms/${request.id.value}", "HttpOnly", "SameSite=Lax")
        assertThat(cookie).`as`("public-base-url is https").contains("Secure")
        assertPrivate(opened)
        assertThat(opened.headers().allValues("Set-Cookie")).noneMatch { it.startsWith("JSESSIONID") }
    }

    @Test
    fun `a replayed, forged or missing code, or no session, gets the same neutral page`() {
        val request = waitingRequest()
        val link = request.interactive!!.url!!.toLocal()
        get(link)

        for (response in listOf(
            get(link),
            get("/forms/${request.id.value}?authCode=forged.code"),
            get("/forms/${request.id.value}"),
            get("/forms/not-a-uuid"),
            get("/forms/${UUID.randomUUID()}"),
        )) {
            assertThat(response.statusCode()).isEqualTo(404)
            assertThat(response.body()).contains("data-testid=\"form-gone\"")
            assertPrivate(response)
        }
    }

    @Test
    fun `the page shows the caller's texts escaped, the fields' definition and the guard`() {
        val request = waitingRequest(title = "Letter <b>Z-1</b>", instructions = "Fill in the motivation.\nCheck the name.")
        val session = open(request)

        val page = get("/forms/${request.id.value}", session)

        assertThat(page.statusCode()).isEqualTo(200)
        assertThat(page.body()).contains("Letter &lt;b&gt;Z-1&lt;/b&gt;").doesNotContain("<b>Z-1</b>")
        assertThat(page.body()).contains("Fill in the motivation.\nCheck the name.")
        assertThat(page.body()).contains("id=\"form-definition\"", "\"kind\":\"generated\"", "\"motivation\"")
        assertThat(guardOf(page.body())).isNotBlank()
        assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("script-src 'self'", "frame-ancestors 'none'")
        assertPrivate(page)
        assertThat(page.headers().firstValue("Set-Cookie")).`as`("the session is renewed").isPresent()
    }

    @Test
    fun `a post without the page's guard does nothing`() {
        val request = waitingRequest()
        val session = open(request)

        val forged = post("/forms/${request.id.value}/submit", session, "guard" to "forged", "data" to """{"name":"Jane","motivation":"Because."}""")

        assertThat(forged.statusCode()).isEqualTo(404)
        assertThat(status(request)).isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    @Test
    fun `invalid data comes back marked, valid data submits and returns the browser to the caller`() {
        val request = waitingRequest(returnUrl = "https://suite.test/back?tab=letters")
        val session = open(request)
        val guard = guardOf(get("/forms/${request.id.value}", session).body())

        val invalid = post("/forms/${request.id.value}/submit", session, "guard" to guard, "data" to """{"name":"Jane"}""")
        assertThat(invalid.statusCode()).isEqualTo(422)
        assertThat(invalid.body()).contains("data-testid=\"form-problems\"", "/motivation", "Jane")
        assertThat(status(request)).isEqualTo(RequestStatus.AWAITING_INPUT)

        val submitted = post("/forms/${request.id.value}/submit", session, "guard" to guard, "data" to """{"name":"Jane","motivation":"Because."}""")
        assertThat(submitted.statusCode()).isEqualTo(303)
        assertThat(submitted.headers().firstValue("Location"))
            .hasValue("https://suite.test/back?tab=letters&epistolaRequestId=${request.id.value}&epistolaRequestStatus=pending")
        assertThat(status(request)).isEqualTo(RequestStatus.PENDING)

        val after = get("/forms/${request.id.value}", session)
        assertThat(after.body()).contains("data-testid=\"form-closed\"", "data-outcome=\"SUBMITTED\"")
    }

    @Test
    fun `cancelling returns the browser with the cancelled status`() {
        val request = waitingRequest(returnUrl = "https://suite.test/back")
        val session = open(request)
        val guard = guardOf(get("/forms/${request.id.value}", session).body())

        val cancelled = post("/forms/${request.id.value}/cancel", session, "guard" to guard)

        assertThat(cancelled.statusCode()).isEqualTo(303)
        assertThat(cancelled.headers().firstValue("Location"))
            .hasValue("https://suite.test/back?epistolaRequestId=${request.id.value}&epistolaRequestStatus=cancelled")
        assertThat(status(request)).isEqualTo(RequestStatus.CANCELLED)
    }

    @Test
    fun `the preview renders the job's own template for valid data and lists what is missing otherwise`() {
        val request = waitingRequest()
        val session = open(request)
        val guard = guardOf(get("/forms/${request.id.value}", session).body())

        val pdf = postJson("/forms/${request.id.value}/preview", session, guard, """{"name":"Jane","motivation":"Because."}""")
        assertThat(pdf.statusCode()).isEqualTo(200)
        assertThat(pdf.headers().firstValue("Content-Type")).hasValue("application/pdf")
        assertThat(String(pdf.body().copyOfRange(0, 5))).isEqualTo("%PDF-")
        assertPrivateBytes(pdf)

        val missing = postJson("/forms/${request.id.value}/preview", session, guard, """{"name":"Jane"}""")
        assertThat(missing.statusCode()).isEqualTo(422)
        assertThat(String(missing.body())).contains("/motivation")

        val unguarded = postJson("/forms/${request.id.value}/preview", session, "forged", """{"name":"Jane","motivation":"Because."}""")
        assertThat(unguarded.statusCode()).isEqualTo(404)
    }

    // ---------------------------------------------------------------------------------------------

    private fun assertPrivate(response: HttpResponse<String>) {
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer")
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
    }

    private fun assertPrivateBytes(response: HttpResponse<ByteArray>) {
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer")
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
    }

    /** Redeems [request]'s link as a browser does, and returns the session cookie it set. */
    private fun open(request: DocumentGenerationRequest): String {
        val opened = get(request.interactive!!.url!!.toLocal())
        assertThat(opened.statusCode()).isEqualTo(303)
        return opened.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
    }

    private fun guardOf(page: String): String = Regex("""name="guard" value="([^"]+)"""").find(page)!!.groupValues[1]

    private fun status(request: DocumentGenerationRequest): RequestStatus = withMediator { GetGenerationJob(request.tenantKey, request.id).query()!!.request.status }

    /** The link's path and query, against this test's server (the link names the configured public base URL). */
    private fun String.toLocal(): String = substringAfter("https://suite.test")

    private fun get(path: String, cookie: String? = null): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().apply { cookie?.let { header("Cookie", it) } }.build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, cookie: String, vararg fields: Pair<String, String>): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Cookie", cookie)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun postJson(path: String, cookie: String, guard: String, body: String): HttpResponse<ByteArray> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Cookie", cookie)
            .header("Content-Type", "application/json")
            .header("X-Form-Guard", guard)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

    /** A request waiting for input on a released template whose contract requires `name` and `motivation`. */
    private fun waitingRequest(title: String? = null, instructions: String? = null, returnUrl: String? = null): DocumentGenerationRequest = withMediator {
        val tenant = TenantKey.of("interactiveformhandlert-${UUID.randomUUID().toString().take(8)}")
        createTenant(tenant, "Form Tenant")
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
        SaveFeatureToggle(tenant, KnownFeatures.INTERACTIVE_GENERATION, true).execute()
        // The server's request threads run on the system clock, not this test's: the link is issued on
        // the same clock it is checked against, or it would look expired (or not yet valid).
        EpistolaClock.withClock(Clock.systemUTC()) { generate(tenant, variant, title, instructions, returnUrl) }
    }

    private fun generate(tenant: TenantKey, variant: VariantId, title: String?, instructions: String?, returnUrl: String?) = withMediator {
        GenerateDocument(
            catalog = testCatalog(tenant, CatalogKey.DEFAULT),
            templateId = variant.templateId.key,
            variantId = variant.key,
            data = json("""{"name": "Jane"}"""),
            filename = "letter.pdf",
            interactive = InteractiveGeneration(InteractiveTrigger.INCOMPLETE, returnUrl = returnUrl, title = title, instructions = instructions),
        ).execute()
    }
}
