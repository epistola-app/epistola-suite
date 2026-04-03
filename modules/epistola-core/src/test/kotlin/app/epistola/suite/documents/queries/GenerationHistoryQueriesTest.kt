package app.epistola.suite.documents.queries

import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TestTemplateBuilder
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

class GenerationHistoryQueriesTest : IntegrationTestBase() {
    private val objectMapper = ObjectMapper()

    private data class TemplateSetup(
        val tenant: Tenant,
        val template: DocumentTemplate,
        val variant: TemplateVariant,
        val version: TemplateVersion,
    )

    private fun createTemplateSetup(tenantName: String, templateName: String): TemplateSetup = withAuthentication {
        val tenant = createTenant(tenantName)
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
        val template = mediator.send(CreateDocumentTemplate(id = templateId, name = templateName))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
        val templateModel = TestTemplateBuilder.buildMinimal(name = templateName)
        val version = mediator.send(UpdateDraft(variantId = variantId, templateModel = templateModel))!!
        TemplateSetup(tenant, template, variant, version)
    }

    private fun generateRequest(setup: TemplateSetup, filename: String) = withAuthentication {
        mediator.send(
            GenerateDocument(
                tenantId = setup.tenant.id,
                templateId = setup.template.id,
                variantId = setup.variant.id,
                versionId = setup.version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", filename),
                filename = filename,
            ),
        )
    }

    @Test
    fun `GetGenerationStats returns correct counts after generation`() {
        val setup = createTemplateSetup("Stats Tenant", "Stats Template")

        // Generate 3 requests
        repeat(3) { i -> generateRequest(setup, "doc-$i.pdf") }

        // Wait for all to complete
        await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .until {
                withAuthentication {
                    GetGenerationStats(setup.tenant.id).query().completed >= 3
                }
            }

        val stats = withAuthentication { GetGenerationStats(setup.tenant.id).query() }

        assertThat(stats.totalGenerated).isGreaterThanOrEqualTo(3)
        assertThat(stats.completed).isGreaterThanOrEqualTo(3)
        assertThat(stats.inQueue).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `GetGenerationStats returns zeros for tenant with no requests`() {
        val tenant = createTenant("Empty Tenant")

        val stats = withAuthentication { GetGenerationStats(tenant.id).query() }

        assertThat(stats.totalGenerated).isEqualTo(0)
        assertThat(stats.inQueue).isEqualTo(0)
        assertThat(stats.completed).isEqualTo(0)
        assertThat(stats.failed).isEqualTo(0)
        assertThat(stats.cancelled).isEqualTo(0)
    }

    @Test
    fun `GetTemplateUsage returns templates ordered by count`() {
        val setup1 = createTemplateSetup("Usage Tenant", "Popular Template")

        // Create a second template under the same tenant
        val setup2 = withAuthentication {
            val tenantId = TenantId(setup1.tenant.id)
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Less Popular"))
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Less Popular")
            val version = mediator.send(UpdateDraft(variantId = variantId, templateModel = templateModel))!!
            TemplateSetup(setup1.tenant, template, variant, version)
        }

        // Generate 3 for template1, 1 for template2
        repeat(3) { i -> generateRequest(setup1, "popular-$i.pdf") }
        generateRequest(setup2, "less-popular.pdf")

        // Wait for all to complete
        await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .until {
                withAuthentication {
                    GetGenerationStats(setup1.tenant.id).query().completed >= 4
                }
            }

        val usage = withAuthentication { GetTemplateUsage(setup1.tenant.id).query() }

        assertThat(usage).hasSizeGreaterThanOrEqualTo(2)
        // First entry should be the most popular template
        assertThat(usage[0].templateKey).isEqualTo(setup1.template.id)
        assertThat(usage[0].count).isGreaterThanOrEqualTo(3)
        // Second entry should be the less popular
        assertThat(usage[1].templateKey).isEqualTo(setup2.template.id)
        assertThat(usage[1].count).isGreaterThanOrEqualTo(1)
        // Ordering: descending by count
        assertThat(usage[0].count).isGreaterThanOrEqualTo(usage[1].count)
    }

    @Test
    fun `GetTemplateUsage returns empty list for tenant with no requests`() {
        val tenant = createTenant("No Usage Tenant")

        val usage = withAuthentication { GetTemplateUsage(tenant.id).query() }

        assertThat(usage).isEmpty()
    }

    @Test
    fun `GetTemplateUsage respects limit`() {
        val setup1 = createTemplateSetup("Limit Tenant", "Template A")

        // Create template B under same tenant
        val setup2 = withAuthentication {
            val tenantId = TenantId(setup1.tenant.id)
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), tenantId)
            val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Template B"))
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            val variant = mediator.send(CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()))!!
            val templateModel = TestTemplateBuilder.buildMinimal(name = "Template B")
            val version = mediator.send(UpdateDraft(variantId = variantId, templateModel = templateModel))!!
            TemplateSetup(setup1.tenant, template, variant, version)
        }

        // Generate 1 request per template
        generateRequest(setup1, "a.pdf")
        generateRequest(setup2, "b.pdf")

        // Wait for both to complete
        await()
            .atMost(15, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .until {
                withAuthentication {
                    GetGenerationStats(setup1.tenant.id).query().completed >= 2
                }
            }

        val usage = withAuthentication { GetTemplateUsage(setup1.tenant.id, limit = 1).query() }

        assertThat(usage).hasSize(1)
    }
}
