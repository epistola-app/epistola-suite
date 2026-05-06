package app.epistola.suite.generation.collect

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.testing.ControllableDocumentGenerationExecutor
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SimulatedConsumerFactory
import app.epistola.suite.testing.TestTemplateBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper

/**
 * Smoke test that proves the Step A test harness works end-to-end:
 *   submit (mediator) → controllable.complete (parks + emits) → consumer.poll
 *   (Touch + Fetch) → row received in the right partition.
 *
 * The JobPoller is disabled via property override so the controllable executor
 * is the only thing that transitions requests to terminal. The default
 * `FakeDocumentGenerationExecutor` is still wired (via IntegrationTestBase's
 * @Import) but never invoked — its only consumer was the JobPoller.
 */
@Timeout(30)
@TestPropertySource(
    properties = [
        "epistola.generation.polling.enabled=false",
    ],
)
class HarnessSmokeIT : IntegrationTestBase() {

    @Autowired
    private lateinit var controllable: ControllableDocumentGenerationExecutor

    @Autowired
    private lateinit var consumers: SimulatedConsumerFactory

    private val objectMapper = ObjectMapper()

    @Test
    fun `submit then controllable complete then consumer poll yields the result`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Invoice")
            val templateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            val version = version(variantId, TestTemplateBuilder.buildMinimal(name = "Invoice"))
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            // Submit a generation request — JobPoller is disabled so it just sits there.
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode().put("x", 1),
                    filename = "doc.pdf",
                    routingKey = "smoke-key",
                ),
            )
        }.then { setup, request ->
            // Sanity: it's still PENDING because the poller is off.
            assertThat(controllable.fetchRequest(request.id)?.status).isEqualTo(RequestStatus.PENDING)

            // Drive the request through to COMPLETED via the harness.
            val documentId = controllable.complete(request)

            // A consumer polls and should receive the result on its assigned partition.
            val alice = consumers.consumer(setup.tenant.id, "alice", "node-1")
            val page = alice.poll()

            assertThat(page.rows).hasSize(1)
            assertThat(page.rows[0].requestId).isEqualTo(request.id)
            assertThat(page.rows[0].documentId).isEqualTo(documentId)
            assertThat(page.rows[0].status.name).isEqualTo("COMPLETED")
            // routingKey was set on submit; the partition is its hash.
            assertThat(page.rows[0].routingKey).isEqualTo("smoke-key")
        }
    }

    @Test
    fun `controllable fail emits a FAILED result that the consumer sees`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Invoice")
            val templateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            val version = version(variantId, TestTemplateBuilder.buildMinimal(name = "Invoice"))
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode(),
                    filename = "f.pdf",
                ),
            )
        }.then { setup, request ->
            controllable.fail(request, "template render exploded")

            val alice = consumers.consumer(setup.tenant.id, "alice", "node-1")
            val page = alice.poll()

            assertThat(page.rows).hasSize(1)
            assertThat(page.rows[0].status.name).isEqualTo("FAILED")
            assertThat(page.rows[0].error).isEqualTo("template render exploded")
            assertThat(page.rows[0].documentId).isNull()
        }
    }

    @Test
    fun `controllable cancel does NOT emit a result`() = scenario {
        given {
            val tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            val template = template(tenant.id, "Invoice")
            val templateId = TemplateId(template.id, CatalogId.default(tenantId))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            val version = version(variantId, TestTemplateBuilder.buildMinimal(name = "Invoice"))
            DocumentSetup(tenant, template, variant, version)
        }.whenever { setup ->
            execute(
                GenerateDocument(
                    tenantId = setup.tenant.id,
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    versionId = setup.version.id,
                    environmentId = null,
                    data = objectMapper.createObjectNode(),
                    filename = "f.pdf",
                ),
            )
        }.then { setup, request ->
            controllable.cancel(request.id)

            val alice = consumers.consumer(setup.tenant.id, "alice", "node-1")
            val page = alice.poll()

            assertThat(page.rows).isEmpty()
        }
    }
}
