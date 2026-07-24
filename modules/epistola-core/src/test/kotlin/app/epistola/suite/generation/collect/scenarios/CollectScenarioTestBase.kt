// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.scenarios

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.ControllableDocumentGenerationExecutor
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.SimulatedConsumerFactory
import app.epistola.suite.testing.TestTemplateBuilder
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper

/**
 * Base class for scenario tests that drive the v0.3 collect lifecycle by hand
 * via [ControllableDocumentGenerationExecutor] and [SimulatedConsumerFactory].
 *
 * The JobPoller is disabled — every transition is test-driven, so timing is
 * deterministic and tests don't have to `await { job.status == COMPLETED }`.
 *
 * Subclasses use [provisionScenario] inside the scenario `given {}` block to
 * set up tenant + template + variant + version, then [submit] to enqueue a
 * generation that will sit in PENDING until the test calls
 * `controllable.complete(...)` or `controllable.fail(...)`.
 */
@Timeout(60)
@TestPropertySource(
    properties = [
        "epistola.generation.polling.enabled=false",
    ],
)
abstract class CollectScenarioTestBase : IntegrationTestBase() {

    @Autowired
    protected lateinit var controllable: ControllableDocumentGenerationExecutor

    @Autowired
    protected lateinit var consumers: SimulatedConsumerFactory

    protected val objectMapper = ObjectMapper()

    /**
     * Submit a generation request and return the resulting `DocumentGenerationRequest`
     * (still PENDING because the JobPoller is disabled). Pass [routingKey] to pin
     * the result to a specific partition; omit to let the emitter fall back to
     * the request id.
     */
    protected fun submit(
        setup: DocumentSetup,
        routingKey: String? = null,
        filename: String = "doc.pdf",
        correlationId: String? = null,
    ): DocumentGenerationRequest = withMediator {
        GenerateDocument(
            tenantId = setup.tenant.id,
            templateId = setup.template.id,
            variantId = setup.variant.id,
            versionId = setup.version.id,
            environmentId = null,
            data = objectMapper.createObjectNode().put("x", 1),
            filename = filename,
            correlationId = correlationId,
            routingKey = routingKey,
        ).execute()
    }
}

/**
 * Provision a fresh tenant + template + variant + version inside a
 * `given { ... }` block. Each call returns an isolated [DocumentSetup] so
 * scenarios can have multiple tenants in flight without interference.
 */
fun ScenarioBuilder.GivenScope.provisionScenario(
    templateName: String = "Invoice",
    tenantName: String = "Test Tenant",
): DocumentSetup {
    val tenant = tenant(tenantName)
    val tenantId = TenantId(tenant.id)
    val template = template(tenant.id, templateName)
    val templateId = TemplateId(template.id, CatalogId.default(tenantId))
    val variant = variant(templateId, "Default")
    val variantId = VariantId(variant.id, templateId)
    val version = version(variantId, TestTemplateBuilder.buildMinimal(name = templateName))
    return DocumentSetup(tenant, template, variant, version)
}
