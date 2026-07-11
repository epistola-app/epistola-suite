package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Integration tests for [CheckRecentUsageCompatibility] (#280).
 *
 * Each test publishes a baseline contract, generates real documents through the
 * generation command (so their payloads are persisted the production way), then edits
 * the draft contract and asserts how the recent-usage replay classifies them.
 */
class CheckRecentUsageCompatibilityTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    private data class Setup(
        val tenant: Tenant,
        val templateId: TemplateId,
        val template: DocumentTemplate,
        val variant: TemplateVariant,
        val version: TemplateVersion,
    )

    private fun schema(json: String): ObjectNode = objectMapper.readValue(json, ObjectNode::class.java)

    private fun createSetup(name: String): Setup = withMediator {
        val tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = name).execute()
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()).execute()!!
        val version = UpdateDraft(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = name)).execute()!!
        Setup(tenant, templateId, template, variant, version)
    }

    /** Publishes [dataModel] as the current (baseline) contract for the template. */
    private fun publishBaselineContract(setup: Setup, dataModel: String) = withMediator {
        UpdateContractVersion(templateId = setup.templateId, dataModel = schema(dataModel)).execute()
        PublishContractVersion(templateId = setup.templateId).execute()
    }

    /** Starts a draft contract carrying [dataModel] — the candidate schema change. */
    private fun draftContract(setup: Setup, dataModel: String) = withMediator {
        CreateContractVersion(templateId = setup.templateId).execute()
        UpdateContractVersion(templateId = setup.templateId, dataModel = schema(dataModel)).execute()
    }

    private fun generate(setup: Setup, data: String) = withMediator {
        GenerateDocument(
            tenantId = setup.tenant.id,
            templateId = setup.template.id,
            variantId = setup.variant.id,
            versionId = setup.version.id,
            environmentId = null,
            data = schema(data),
            filename = "doc.pdf",
        ).execute()
    }

    private fun check(setup: Setup): RecentUsageImpact = withMediator { CheckRecentUsageCompatibility(templateId = setup.templateId).query() }

    @Test
    fun `flags recent payloads that omit a newly-required field`() {
        val setup = createSetup("Required Field Tenant")
        publishBaselineContract(setup, """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")

        // 3 recent documents did not supply taxId; 2 did.
        repeat(3) { generate(setup, """{"name":"Acme"}""") }
        repeat(2) { generate(setup, """{"name":"Acme","taxId":"NL123"}""") }
        drainGenerationJobs(setup.tenant.id)

        // The schema change makes taxId required.
        draftContract(
            setup,
            """{"type":"object","properties":{"name":{"type":"string"},"taxId":{"type":"string"}},"required":["name","taxId"]}""",
        )

        val impact = check(setup)

        assertThat(impact.applicable).isTrue()
        assertThat(impact.sampledDocuments).isEqualTo(5)
        assertThat(impact.distinctShapes).isEqualTo(2)
        assertThat(impact.failingShapes).isEqualTo(1)
        assertThat(impact.failingDocuments).isEqualTo(3)
        assertThat(impact.compatible).isFalse()
        assertThat(impact.fields).anySatisfy {
            assertThat(it.path).isEqualTo("taxId")
            assertThat(it.failingDocuments).isEqualTo(3)
        }
    }

    @Test
    fun `flags recent payloads whose value falls outside a narrowed enum`() {
        val setup = createSetup("Enum Narrowing Tenant")
        publishBaselineContract(
            setup,
            """{"type":"object","properties":{"status":{"type":"string","enum":["A","B","C"]}}}""",
        )

        generate(setup, """{"status":"A"}""")
        generate(setup, """{"status":"B"}""")
        repeat(2) { generate(setup, """{"status":"C"}""") }
        drainGenerationJobs(setup.tenant.id)

        // Drop "C" from the allowed set.
        draftContract(setup, """{"type":"object","properties":{"status":{"type":"string","enum":["A","B"]}}}""")

        val impact = check(setup)

        assertThat(impact.applicable).isTrue()
        assertThat(impact.sampledDocuments).isEqualTo(4)
        assertThat(impact.distinctShapes).isEqualTo(3)
        assertThat(impact.failingShapes).isEqualTo(1)
        assertThat(impact.failingDocuments).isEqualTo(2)
        assertThat(impact.fields).anySatisfy { assertThat(it.path).isEqualTo("status") }
    }

    @Test
    fun `reports no impact for a compatible change`() {
        val setup = createSetup("Compatible Change Tenant")
        publishBaselineContract(setup, """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")

        repeat(2) { generate(setup, """{"name":"Acme"}""") }
        drainGenerationJobs(setup.tenant.id)

        // Adding an optional field is backwards compatible — nothing recent could regress.
        draftContract(
            setup,
            """{"type":"object","properties":{"name":{"type":"string"},"email":{"type":"string"}},"required":["name"]}""",
        )

        val impact = check(setup)

        assertThat(impact.applicable).isFalse()
        assertThat(impact.compatible).isTrue()
        assertThat(impact.failingDocuments).isEqualTo(0)
    }

    @Test
    fun `is not applicable when there is no draft contract`() {
        val setup = createSetup("No Draft Tenant")
        publishBaselineContract(setup, """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")

        repeat(2) { generate(setup, """{"name":"Acme"}""") }
        drainGenerationJobs(setup.tenant.id)

        val impact = check(setup)

        assertThat(impact.applicable).isFalse()
    }

    @Test
    fun `applicable but empty when a breaking change has no recent usage`() {
        val setup = createSetup("No Usage Tenant")
        publishBaselineContract(setup, """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")

        // No documents generated.
        draftContract(
            setup,
            """{"type":"object","properties":{"name":{"type":"string"},"taxId":{"type":"string"}},"required":["name","taxId"]}""",
        )

        val impact = check(setup)

        assertThat(impact.applicable).isTrue()
        assertThat(impact.sampledDocuments).isEqualTo(0)
        assertThat(impact.failingDocuments).isEqualTo(0)
        assertThat(impact.compatible).isTrue()
    }
}
