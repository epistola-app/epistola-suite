// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.applications.commands.CreateApplication
import app.epistola.suite.applications.commands.SetApplicationReturnOrigins
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.CancelFormOutcome
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.CancelInteractiveForm
import app.epistola.suite.documents.commands.ExpireInteractiveForms
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.SubmitFormOutcome
import app.epistola.suite.documents.commands.SubmitInteractiveForm
import app.epistola.suite.documents.interactive.FormOutcome
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveLink
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.model.CancelReason
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.GetInteractiveForm
import app.epistola.suite.documents.queries.InteractiveFormView
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PrincipalApplication
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ThenScope
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.UUID

/**
 * Interactive generation (contract ADR 0011) at acceptance: whether a request waits for input, what
 * its form asks for, the limits on its link, and cancelling it.
 */
@Timeout(60)
class InteractiveGenerationIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    private val contract = """
        {
          "type": "object",
          "properties": {
            "customer": {
              "type": "object",
              "properties": { "name": { "type": "string" }, "phone": { "type": "string" } },
              "required": ["name"]
            },
            "invoiceDate": { "type": "string", "format": "date" }
          },
          "required": ["customer", "invoiceDate"]
        }
    """

    private val complete = """{"customer": {"name": "Ada"}, "invoiceDate": "2026-11-19"}"""

    /** Prints `customer.name`, so the analysis knows the template reads it. */
    private fun templateReadingName(): TemplateDocument {
        val text = Node(
            id = "text",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to "customer.name"))),
                        ),
                    ),
                ),
            ),
        )
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot")), "text" to text),
            slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("text"))),
            themeRef = ThemeRef.Inherit,
        )
    }

    private fun withReleasedTemplate(featureOn: Boolean = true, block: ThenScope.(DocumentSetup) -> Unit) = scenario {
        given {
            val tenant = tenant("Interactive Tenant")
            val template = template(tenant.id, "Invoice")
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            UpdateDataContract(
                templateId = templateId,
                dataModel = json(contract),
                // An example that would satisfy the contract: a job must never fall back to it.
                dataExamples = listOf(DataExample("example-1", "Example 1", json(complete))),
                catalogVersion = null,
            ).let(::execute)
            val published = released(variantId, templateReadingName())
            if (featureOn) execute(SaveFeatureToggle(tenant.id, KnownFeatures.INTERACTIVE_GENERATION, true))
            DocumentSetup(tenant, template, variant, published)
        }.whenever { it }.then { setup, _ -> block(setup) }
    }

    private fun DocumentSetup.generate(data: String, interactive: InteractiveGeneration?) = GenerateDocument(
        catalog = testCatalog(tenant.id, CatalogKey.DEFAULT),
        templateId = template.id,
        variantId = variant.id,
        data = json(data),
        filename = "invoice.pdf",
        interactive = interactive,
    )

    private val incomplete = InteractiveGeneration(trigger = InteractiveTrigger.INCOMPLETE)

    @Test
    fun `without the feature the request is accepted as before and nothing is echoed`() = withReleasedTemplate(featureOn = false) { setup ->
        val request = execute(setup.generate("{}", incomplete))

        assertThat(request.status).isEqualTo(RequestStatus.PENDING)
        assertThat(request.interactive).isNull()
    }

    @Test
    fun `incomplete with complete data renders at once, and still echoes the form`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate(complete, incomplete))

        assertThat(request.status).isEqualTo(RequestStatus.PENDING)
        val interactive = requireNotNull(request.interactive)
        assertThat(interactive.trigger).isEqualTo(InteractiveTrigger.INCOMPLETE)
        assertThat(interactive.url).isNull()
        assertThat(interactive.expiresAt).isNull()
        assertThat(interactive.missingFields).isEmpty()
        assertThat(interactive.editedFields).isEmpty()
    }

    @Test
    fun `incomplete with missing data waits for input and asks for what is missing`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("""{"customer": {}}""", incomplete))

        assertThat(request.status).isEqualTo(RequestStatus.AWAITING_INPUT)
        val interactive = requireNotNull(request.interactive)
        assertThat(interactive.url).matches("https://suite\\.test/forms/[A-Za-z0-9_-]{43}")
        assertThat(interactive.expiresAt).isNotNull()
        assertThat(interactive.missingFields.map { it.path to it.required }).contains("/customer/name" to true, "/invoiceDate" to true)
        assertThat(interactive.editedFields).isNull()
    }

    @Test
    fun `incomplete with an invalid value waits for input`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("""{"customer": {"name": "Ada"}, "invoiceDate": 42}""", incomplete))

        assertThat(request.status).isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    @Test
    fun `empty data is an empty form, never a data example`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", incomplete))

        assertThat(request.status).isEqualTo(RequestStatus.AWAITING_INPUT)
        assertThat(request.interactive!!.missingFields.map { it.path }).contains("/customer", "/invoiceDate")
    }

    @Test
    fun `always waits for input even when the data is complete`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate(complete, InteractiveGeneration(trigger = InteractiveTrigger.ALWAYS)))

        assertThat(request.status).isEqualTo(RequestStatus.AWAITING_INPUT)
        assertThat(request.interactive!!.url).isNotNull()
    }

    @Test
    fun `a waiting request is never claimed by a worker`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", incomplete))

        drainGenerationJobs(setup.tenant.id)

        assertThat(job(request).status).isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    @Test
    fun `the job status hands out the same link while the request waits`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", incomplete))
        val url = requireNotNull(request.interactive?.url)

        assertThat(job(request).interactive?.url).isEqualTo(url)
        val listed = query(ListGenerationJobs(setup.tenant.id, status = RequestStatus.AWAITING_INPUT)).single { it.id == request.id }
        assertThat(listed.interactive?.url).isEqualTo(url)
    }

    @Test
    fun `an expiry above the installation's maximum is refused`() = withReleasedTemplate { setup ->
        // The default maximum is 7 days.
        val error = assertThrows<ValidationException> {
            execute(setup.generate("{}", InteractiveGeneration(InteractiveTrigger.INCOMPLETE, expiresInMinutes = 7 * 24 * 60 + 1)))
        }

        assertThat(error.field).isEqualTo("interactive.expiresInMinutes")
        assertThat(error.code).isEqualTo(ValidationCode.MAXIMUM)
    }

    @Test
    fun `a return URL on an origin that is not allowed is refused`() = withReleasedTemplate { setup ->
        val error = assertThrows<ValidationException> {
            execute(setup.generate("{}", InteractiveGeneration(InteractiveTrigger.INCOMPLETE, returnUrl = "https://cases.example.org/z/1")))
        }

        assertThat(error.field).isEqualTo("interactive.returnUrl")
        assertThat(error.code).isEqualTo(ValidationCode.URL_NOT_ALLOWED)
    }

    @Test
    fun `a look-alike of the installation's own origin is refused`() = withReleasedTemplate { setup ->
        val error = assertThrows<ValidationException> {
            execute(
                setup.generate(
                    "{}",
                    InteractiveGeneration(InteractiveTrigger.INCOMPLETE, links = listOf(InteractiveLink("Case", "https://suite.test.evil.example/x"))),
                ),
            )
        }

        assertThat(error.field).isEqualTo("interactive.links[0].url")
        assertThat(error.code).isEqualTo(ValidationCode.URL_NOT_ALLOWED)
    }

    @Test
    fun `the installation's own origin is always allowed`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", InteractiveGeneration(InteractiveTrigger.INCOMPLETE, returnUrl = "https://suite.test/tenants/x/generation-history")))

        assertThat(request.status).isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    @Test
    fun `an application is held to its own return origins, not the installation's`() = withReleasedTemplate { setup ->
        val caseSystem = applicationPrincipal(setup.tenant.id, origins = listOf("https://Cases.Example.org/"))
        val back = InteractiveGeneration(InteractiveTrigger.INCOMPLETE, returnUrl = "https://cases.example.org/zaken/Z-1")

        val accepted = runAs(caseSystem) { setup.generate("{}", back).execute() }
        assertThat(accepted.status).isEqualTo(RequestStatus.AWAITING_INPUT)

        val other = applicationPrincipal(setup.tenant.id, origins = emptyList())
        val error = assertThrows<ValidationException> { runAs(other) { setup.generate("{}", back).execute() } }
        assertThat(error.code).isEqualTo(ValidationCode.URL_NOT_ALLOWED)
    }

    @Test
    fun `an application's return origins are origins only`() = withReleasedTemplate { setup ->
        val application = execute(CreateApplication(tenantKey = setup.tenant.id, name = "Cases", roles = TenantRole.entries.toSet())).id

        val error = assertThrows<ValidationException> {
            execute(SetApplicationReturnOrigins(setup.tenant.id, application, listOf("https://cases.example.org/zaken")))
        }

        assertThat(error.field).isEqualTo("origins[0]")
    }

    @Test
    fun `cancelling a waiting request records the caller and delivers one cancelled result without data`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("""{"customer": {}}""", incomplete))

        execute(CancelGenerationJob(setup.tenant.id, request.id))

        val cancelled = job(request)
        assertThat(cancelled.status).isEqualTo(RequestStatus.CANCELLED)
        assertThat(cancelled.cancelReason).isEqualTo(CancelReason.CALLER)
        assertThat(cancelled.interactive!!.url).isNull()
        assertThat(results(request)).containsExactly(ResultLine("CANCELLED", "CALLER", hasData = false))
    }

    @Test
    fun `cancelling a request without a form delivers no result, as before`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate(complete, interactive = null))

        execute(CancelGenerationJob(setup.tenant.id, request.id))

        assertThat(job(request).cancelReason).isEqualTo(CancelReason.CALLER)
        assertThat(results(request)).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------
    // The form: looking it up, submitting, cancelling, expiring
    // ---------------------------------------------------------------------------------------------

    private fun DocumentGenerationRequest.token(): String = requireNotNull(interactive?.url).substringAfterLast('/')

    @Test
    fun `the link opens the form with the bound contract, the caller's data and texts`() = withReleasedTemplate { setup ->
        val request = execute(
            setup.generate(
                """{"customer": {"phone": "555"}}""",
                InteractiveGeneration(InteractiveTrigger.INCOMPLETE, title = "Invoice, case Z-1", instructions = "Fill in the date.\nCheck the name."),
            ),
        )

        val form = query(GetInteractiveForm(request.token()))

        assertThat(form).isInstanceOf(InteractiveFormView.Open::class.java)
        form as InteractiveFormView.Open
        assertThat(form.requestId).isEqualTo(request.id)
        assertThat(form.title).isEqualTo("Invoice, case Z-1")
        assertThat(form.instructions).isEqualTo("Fill in the date.\nCheck the name.")
        assertThat(form.data).isEqualTo(json("""{"customer": {"phone": "555"}}"""))
        assertThat(form.dataModel!!.get("required").values().map { it.asString() }).containsExactly("customer", "invoiceDate")
        assertThat(form.analysis.valid).isFalse()
    }

    @Test
    fun `an unknown or malformed token finds no form`() = withReleasedTemplate { _ ->
        assertThat(query(GetInteractiveForm("A".repeat(43)))).isNull()
        assertThat(query(GetInteractiveForm("not-a-token"))).isNull()
        assertThat(execute(SubmitInteractiveForm("A".repeat(43), json(complete)))).isNull()
    }

    @Test
    fun `invalid data is refused on the server and the form stays open`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", incomplete))

        val outcome = execute(SubmitInteractiveForm(request.token(), json("""{"customer": {"name": "Ada"}}""")))

        assertThat(outcome).isInstanceOf(SubmitFormOutcome.Invalid::class.java)
        assertThat((outcome as SubmitFormOutcome.Invalid).analysis.missingFields.map { it.path }).contains("/invoiceDate")
        assertThat(job(request).status).isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    @Test
    fun `submitting stores the data, records what the person changed and makes the request pending, once`() = withReleasedTemplate { setup ->
        val request = execute(
            setup.generate(
                """{"customer": {"name": "Ada", "phone": "555"}, "note": "kept"}""",
                InteractiveGeneration(InteractiveTrigger.ALWAYS, returnUrl = "https://suite.test/back?tab=letters"),
            ),
        )
        val submitted = json("""{"customer": {"name": "Ada Lovelace"}, "invoiceDate": "2026-11-19", "note": "kept"}""")

        val outcome = execute(SubmitInteractiveForm(request.token(), submitted))

        assertThat(outcome).isEqualTo(
            SubmitFormOutcome.Submitted(
                request.id,
                setup.tenant.id,
                "https://suite.test/back?tab=letters&epistolaRequestId=${request.id.value}&epistolaRequestStatus=pending",
            ),
        )
        val pending = job(request)
        assertThat(pending.status).isIn(RequestStatus.PENDING, RequestStatus.IN_PROGRESS, RequestStatus.COMPLETED)
        assertThat(pending.data).isEqualTo(submitted)
        assertThat(pending.submittedAt).isNotNull()
        val interactive = requireNotNull(pending.interactive)
        assertThat(interactive.url).isNull()
        assertThat(interactive.editedFields).containsExactly("/customer/name", "/customer/phone", "/invoiceDate")

        val again = execute(SubmitInteractiveForm(request.token(), submitted))
        assertThat(again).isEqualTo(SubmitFormOutcome.Closed(FormOutcome.SUBMITTED, "https://suite.test/back?tab=letters"))
    }

    @Test
    fun `a submitted request renders, and its result carries the data and what was edited`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("""{"customer": {}}""", incomplete))
        execute(SubmitInteractiveForm(request.token(), json(complete)))

        drainGenerationJobs(setup.tenant.id)
        awaitIdleJobPoller()

        assertThat(job(request).status).isEqualTo(RequestStatus.COMPLETED)
        val line = results(request).single()
        assertThat(line).isEqualTo(ResultLine("COMPLETED", null, hasData = true))
        assertThat(resultEditedFields(request)).containsExactly("/customer/name", "/invoiceDate")
    }

    @Test
    fun `cancelling on the form records the person and delivers a cancelled result`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", InteractiveGeneration(InteractiveTrigger.INCOMPLETE, returnUrl = "https://suite.test/back")))

        val outcome = execute(CancelInteractiveForm(request.token()))

        assertThat(outcome).isEqualTo(
            CancelFormOutcome.Cancelled(request.id, "https://suite.test/back?epistolaRequestId=${request.id.value}&epistolaRequestStatus=cancelled"),
        )
        assertThat(job(request).cancelReason).isEqualTo(CancelReason.USER)
        assertThat(results(request)).containsExactly(ResultLine("CANCELLED", "USER", hasData = false))
        assertThat(query(GetInteractiveForm(request.token()))).isEqualTo(
            InteractiveFormView.Closed(request.id, setup.tenant.id, FormOutcome.CANCELLED, "https://suite.test/back"),
        )
    }

    @Test
    fun `an expired link closes the form at once, and the sweep cancels the request`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", InteractiveGeneration(InteractiveTrigger.INCOMPLETE, expiresInMinutes = 60)))

        testClock.advanceBy(Duration.ofMinutes(61))

        assertThat(query(GetInteractiveForm(request.token()))).isEqualTo(
            InteractiveFormView.Closed(request.id, setup.tenant.id, FormOutcome.EXPIRED, null),
        )
        assertThat(execute(SubmitInteractiveForm(request.token(), json(complete)))).isEqualTo(SubmitFormOutcome.Closed(FormOutcome.EXPIRED, null))

        execute(ExpireInteractiveForms())

        val expired = job(request)
        assertThat(expired.status).isEqualTo(RequestStatus.CANCELLED)
        assertThat(expired.cancelReason).isEqualTo(CancelReason.EXPIRED)
        assertThat(results(request)).containsExactly(ResultLine("CANCELLED", "EXPIRED", hasData = false))
    }

    @Test
    fun `the sweep leaves forms that have not expired`() = withReleasedTemplate { setup ->
        val request = execute(setup.generate("{}", InteractiveGeneration(InteractiveTrigger.INCOMPLETE, expiresInMinutes = 60)))

        testClock.advanceBy(Duration.ofMinutes(59))
        execute(ExpireInteractiveForms())

        assertThat(job(request).status).isEqualTo(RequestStatus.AWAITING_INPUT)
    }

    /** A caller authenticated as a new application of [tenant] that allows [origins], with every role. */
    private fun applicationPrincipal(tenant: TenantKey, origins: List<String>): EpistolaPrincipal {
        val application = withMediator {
            val id = CreateApplication(tenantKey = tenant, name = "Case system", roles = TenantRole.entries.toSet()).execute().id
            SetApplicationReturnOrigins(tenant, id, origins).execute()
            id
        }
        val userId = UserKey.of(UUID.randomUUID())
        return EpistolaPrincipal(
            userId = userId,
            externalId = "apikey:$userId",
            email = "apikey-$userId@npa.epistola",
            displayName = "Case system",
            tenantMemberships = mapOf(tenant to TenantRole.entries.toSet()),
            currentTenantId = tenant,
            application = PrincipalApplication(application, seesAllDocuments = false, ownsUnattributedDocuments = false),
        )
    }

    private fun ThenScope.job(request: DocumentGenerationRequest): DocumentGenerationRequest = requireNotNull(query(GetGenerationJob(request.tenantKey, request.id))).request

    private fun resultEditedFields(request: DocumentGenerationRequest): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT jsonb_array_elements_text(edited_fields) FROM generation_results WHERE request_id = :id")
            .bind("id", request.id.value)
            .mapTo(String::class.java)
            .list()
    }

    private data class ResultLine(val status: String, val cancelReason: String?, val hasData: Boolean)

    // Read straight from the table: collect's own query needs a registered consumer node, and the
    // assertion is about what was emitted, not how it is streamed (NdjsonResultStreamTest).
    private fun results(request: DocumentGenerationRequest): List<ResultLine> = jdbi.withHandle<List<ResultLine>, Exception> { handle ->
        handle.createQuery("SELECT status, cancel_reason, data IS NOT NULL AS has_data FROM generation_results WHERE request_id = :id ORDER BY sequence")
            .bind("id", request.id.value)
            .map { rs, _ -> ResultLine(rs.getString("status"), rs.getString("cancel_reason"), rs.getBoolean("has_data")) }
            .list()
    }
}
