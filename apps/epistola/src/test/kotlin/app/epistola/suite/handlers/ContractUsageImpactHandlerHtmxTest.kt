package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Server-contract cover for the recent-usage impact dialog (#280): the
 * `contract/usage-impact` route renders the `contract-usage-impact` fragment and
 * each of its branches (regression, backwards-compatible) renders without a
 * Thymeleaf error. The replay logic itself is covered by
 * `CheckRecentUsageCompatibilityTest`.
 */
class ContractUsageImpactHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val objectMapper = ObjectMapper()

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private data class Seeded(
        val tenantKey: TenantKey,
        val templateId: TemplateId,
    ) {
        val url: String get() =
            "/tenants/${tenantKey.value}/templates/${templateId.catalogKey.value}/${templateId.key.value}/contract/usage-impact"
    }

    private fun seedTemplate(name: String): Seeded = withMediator {
        val tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = name).execute()
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()).execute()!!
        UpdateDraft(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = name)).execute()!!
        Seeded(tenant.id, templateId)
    }

    private fun publishBaseline(seeded: Seeded, dataModel: String) = withMediator {
        UpdateContractVersion(templateId = seeded.templateId, dataModel = schema(dataModel)).execute()
        PublishContractVersion(templateId = seeded.templateId).execute()
    }

    private fun draftContract(seeded: Seeded, dataModel: String) = withMediator {
        CreateContractVersion(templateId = seeded.templateId).execute()
        UpdateContractVersion(templateId = seeded.templateId, dataModel = schema(dataModel)).execute()
    }

    @Test
    fun `renders the applicable-but-no-usage state for a breaking draft with no completed generations`() {
        // The regression breakdown itself (counts, failing fields) is covered by
        // CheckRecentUsageCompatibilityTest, which can drain the job poller; the app
        // context here has no poller, so this asserts the sibling branch: a breaking
        // draft change with nothing recent to replay against.
        val seeded = seedTemplate("Usage Impact No Usage")
        publishBaseline(seeded, """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
        draftContract(
            seeded,
            """{"type":"object","properties":{"name":{"type":"string"},"taxId":{"type":"string"}},"required":["name","taxId"]}""",
        )

        val response = getHtmx(seeded.url)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("No completed generations")
    }

    @Test
    fun `renders the backwards-compatible state when the draft adds only an optional field`() {
        val seeded = seedTemplate("Usage Impact Compatible")
        publishBaseline(seeded, """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
        draftContract(
            seeded,
            """{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}},"required":["name"]}""",
        )

        val response = getHtmx(seeded.url)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("backwards compatible")
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.set("HX-Request", "true")
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<String>(headers), String::class.java)
    }
}
