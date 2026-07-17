package app.epistola.suite.quality

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RecordManualFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.GetQualityFinding
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The quality report's server contract, at the handler level rather than in a browser — the
 * deterministic half of Phase 1's verification (see `docs/testing.md`).
 *
 * The module ships its own Thymeleaf templates under `templates/quality`, so this also proves they
 * resolve against the host app's classpath and chrome, which only a full-app context can show.
 */
class QualityHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val source = QualitySourceId("example")

    private fun form() = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    private fun htmxForm() = form().apply { add("HX-Request", "true") }

    /** A tenant with the alpha feature on and one variant to hang findings from. */
    private fun scenario(): QualitySubject {
        val tenant = createTenant("Quality UI")
        return withMediator {
            SaveFeatureToggle(TenantKey.of(tenant.id.value), KnownFeatures.QUALITY, enabled = true).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            QualitySubject.of(variantId)
        }
    }

    private fun submit(
        subject: QualitySubject,
        vararg findings: SubmittedFinding,
    ) = withMediator { SubmitQualityFindings(source, subject, findings.toList()).execute() }

    private fun finding(
        fingerprint: String = "fp-1",
        message: String = "This text block is empty",
        severity: QualitySeverity = QualitySeverity.WARNING,
    ) = SubmittedFinding(
        ruleId = "example.empty-text",
        severity = severity,
        fingerprint = fingerprint,
        message = message,
        nodeIds = listOf("node-1"),
    )

    private fun onlyFinding(subject: QualitySubject) = withMediator {
        ListQualityFindings(subject.tenantKey).query().items.single()
    }

    private fun post(
        url: String,
        vararg fields: Pair<String, String>,
    ) = restTemplate.postForEntity(
        url,
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } },
            htmxForm(),
        ),
        String::class.java,
    )

    @Test
    fun `the report lists a submitted finding`() {
        val subject = scenario()
        submit(subject, finding())

        val response = restTemplate.getForEntity("/tenants/${subject.tenantKey.value}/quality", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("This text block is empty")
        assertThat(body).contains("example.empty-text")
        // Alpha feature: the header carries the maturity badge rather than presenting as finished.
        assertThat(body).contains("Alpha")
    }

    /** The report opens on what is actionable, not on a wall of closed findings. */
    @Test
    fun `the report defaults to open findings and hides resolved ones`() {
        val subject = scenario()
        submit(subject, finding(message = "Still broken"))
        // A full-set submission that omits it: the ledger resolves it with nobody clicking.
        submit(subject, finding(fingerprint = "fp-2", message = "A different problem"))

        val response = restTemplate.getForEntity("/tenants/${subject.tenantKey.value}/quality", String::class.java)

        val body = response.body!!
        assertThat(body).contains("A different problem")
        assertThat(body).doesNotContain("Still broken")
    }

    @Test
    fun `status=all shows resolved findings too`() {
        val subject = scenario()
        submit(subject, finding(message = "Auto-resolved"))
        submit(subject)

        val response = restTemplate.getForEntity(
            "/tenants/${subject.tenantKey.value}/quality?status=",
            String::class.java,
        )

        assertThat(response.body!!).contains("Auto-resolved")
    }

    @Test
    fun `the detail page shows the finding and its evidence`() {
        val subject = scenario()
        submit(subject, finding())
        val key = onlyFinding(subject).key

        val response = restTemplate.getForEntity(
            "/tenants/${subject.tenantKey.value}/quality/${key.value}",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body).contains("This text block is empty")
        assertThat(body).contains("node-1")
    }

    @Test
    fun `ignoring with a reason moves the finding to IGNORED and records the reason`() {
        val subject = scenario()
        submit(subject, finding())
        val key = onlyFinding(subject).key

        val response = post(
            "/tenants/${subject.tenantKey.value}/quality/${key.value}/ignore",
            "reason" to "Intentional blank spacer",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val reread = withMediator { GetQualityFinding(subject.tenantKey, key).query() }!!
        assertThat(reread.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED)
        assertThat(reread.ignoreReason).isEqualTo("Intentional blank spacer")
    }

    /**
     * An ignore without a reason is indistinguishable from a bug later, so the reason is required —
     * and the error has to land in the still-open dialog, not in the detail body the success path
     * swaps (which would close the dialog and take the message with it).
     */
    @Test
    fun `ignoring without a reason re-renders the dialog with the error`() {
        val subject = scenario()
        submit(subject, finding())
        val key = onlyFinding(subject).key

        val response = post("/tenants/${subject.tenantKey.value}/quality/${key.value}/ignore", "reason" to "")

        assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#quality-ignore-dialog-body")
        assertThat(response.body!!).contains("quality-ignore-error")
        assertThat(withMediator { GetQualityFinding(subject.tenantKey, key).query() }!!.effectiveStatus)
            .isEqualTo(EffectiveQualityStatus.OPEN)
    }

    @Test
    fun `un-ignoring returns the finding to OPEN`() {
        val subject = scenario()
        submit(subject, finding())
        val key = onlyFinding(subject).key
        post("/tenants/${subject.tenantKey.value}/quality/${key.value}/ignore", "reason" to "Not applicable")

        post("/tenants/${subject.tenantKey.value}/quality/${key.value}/unignore")

        assertThat(withMediator { GetQualityFinding(subject.tenantKey, key).query() }!!.effectiveStatus)
            .isEqualTo(EffectiveQualityStatus.OPEN)
    }

    @Test
    fun `a comment is added and rendered back`() {
        val subject = scenario()
        submit(subject, finding())
        val key = onlyFinding(subject).key

        val response = post(
            "/tenants/${subject.tenantKey.value}/quality/${key.value}/comments",
            "body" to "Agreed, this is a real problem",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("Agreed, this is a real problem")
    }

    @Test
    fun `a finding is raised by hand and lands in the report`() {
        val subject = scenario()

        val response = post(
            "/tenants/${subject.tenantKey.value}/quality",
            "template" to "${subject.catalogKey.value}/${subject.templateKey.value}",
            "variantKey" to subject.variantKey!!,
            "severity" to "ERROR",
            "message" to "The tone is wrong for a reminder",
        )

        // HX-Redirect rather than a swap: whether the new finding belongs under the active filters
        // is a question only a re-run query answers.
        assertThat(response.headers.getFirst("HX-Redirect"))
            .isEqualTo("/tenants/${subject.tenantKey.value}/quality")

        val raised = onlyFinding(subject)
        assertThat(raised.message).isEqualTo("The tone is wrong for a reminder")
        assertThat(raised.severity).isEqualTo(QualitySeverity.ERROR)
        // Nothing reconciles a human's note away, so the UI must offer an explicit Resolve.
        assertThat(raised.reconciled).isFalse()
    }

    @Test
    fun `a manual finding can be resolved by hand`() {
        val subject = scenario()
        val key = withMediator { RecordManualFinding(subject, "Reviewed and wrong").execute() }

        post("/tenants/${subject.tenantKey.value}/quality/${key.value}/resolve")

        assertThat(withMediator { GetQualityFinding(subject.tenantKey, key).query() }!!.effectiveStatus)
            .isEqualTo(EffectiveQualityStatus.RESOLVED)
    }

    @Test
    fun `the variant picker loads the chosen template's variants`() {
        val subject = scenario()

        val response = restTemplate.exchange(
            "/tenants/${subject.tenantKey.value}/quality/variants" +
                "?template=${subject.catalogKey.value}/${subject.templateKey.value}",
            org.springframework.http.HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!).contains("Default")
    }

    @Test
    fun `an unknown finding is a 404`() {
        val subject = scenario()

        val response = restTemplate.getForEntity(
            "/tenants/${subject.tenantKey.value}/quality/${QualityFindingKey.generate().value}",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
