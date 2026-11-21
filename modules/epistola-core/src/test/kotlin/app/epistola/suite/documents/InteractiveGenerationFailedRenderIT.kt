// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.RedeemFormLink
import app.epistola.suite.documents.commands.SubmitInteractiveForm
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.ControllableDocumentGenerationExecutor
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * A render that fails after the person submitted the form (contract ADR 0011): the failed result
 * still carries the data the person submitted and what they changed, so the caller does not lose
 * it. The job poller is off, so the request fails through the harness, which emits its result
 * through the same [app.epistola.suite.generation.collect.commands.EmitGenerationResult] a real
 * render failure does, and nothing completes it first.
 */
@Timeout(30)
@TestPropertySource(properties = ["epistola.generation.polling.enabled=false"])
class InteractiveGenerationFailedRenderIT : IntegrationTestBase() {

    @Autowired
    private lateinit var controllable: ControllableDocumentGenerationExecutor

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    @Test
    fun `a render that fails after the form was submitted delivers the submitted data and what was edited`() = scenario {
        given {
            val tenant = tenant("Failed Render Tenant")
            val template = template(tenant.id, "Letter")
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            val variant = variant(templateId, "Default")
            UpdateDataContract(
                templateId = templateId,
                dataModel = json("""{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}"""),
                dataExamples = listOf(DataExample("example-1", "Example 1", json("""{"name": "Example"}"""))),
                catalogVersion = null,
            ).let(::execute)
            val published = released(VariantId(variant.id, templateId), TestTemplateBuilder.buildMinimal(name = "Letter"))
            execute(SaveFeatureToggle(tenant.id, KnownFeatures.INTERACTIVE_GENERATION, true))
            DocumentSetup(tenant, template, variant, published)
        }.whenever { setup ->
            val request = execute(
                GenerateDocument(
                    catalog = testCatalog(setup.tenant.id, CatalogKey.DEFAULT),
                    templateId = setup.template.id,
                    variantId = setup.variant.id,
                    data = json("{}"),
                    filename = "letter.pdf",
                    interactive = InteractiveGeneration(InteractiveTrigger.INCOMPLETE),
                ),
            )
            val url = requireNotNull(request.interactive?.url)
            val session = requireNotNull(execute(RedeemFormLink(request.id, url.substringAfter("authCode="))))
            requireNotNull(execute(SubmitInteractiveForm(request.id, session.token, json("""{"name": "Ada"}"""))))
            request.id
        }.then { setup, requestId ->
            val submitted = requireNotNull(query(GetGenerationJob(setup.tenant.id, requestId))).request
            assertThat(submitted.status).isEqualTo(RequestStatus.PENDING)

            controllable.fail(submitted, "The renderer gave up")

            val result = failedResult(requestId)
            assertThat(result.status).isEqualTo("FAILED")
            assertThat(objectMapper.readTree(result.data)).isEqualTo(json("""{"name": "Ada"}"""))
            val edited: List<String> = objectMapper.readTree(result.editedFields).toList().map { it.asString() }
            assertThat(edited).containsExactly("/name")
        }
    }

    private data class ResultRow(val status: String, val data: String?, val editedFields: String?)

    // Read straight from the collect table: what a collector would be handed for this request.
    private fun failedResult(requestId: GenerationRequestKey): ResultRow = jdbi.withHandle<ResultRow, Exception> { handle ->
        handle.createQuery("SELECT status, data::text AS data, edited_fields::text AS edited_fields FROM generation_results WHERE request_id = :id")
            .bind("id", requestId.value)
            .map { rs, _ -> ResultRow(rs.getString("status"), rs.getString("data"), rs.getString("edited_fields")) }
            .single()
    }
}
