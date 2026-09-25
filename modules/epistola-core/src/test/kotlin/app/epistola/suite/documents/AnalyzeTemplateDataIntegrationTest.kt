// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.queries.AnalyzeTemplateData
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.validation.TemplateDataInvalidException
import app.epistola.suite.testing.DocumentSetup
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ThenScope
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

@Timeout(30)
class AnalyzeTemplateDataIntegrationTest : IntegrationTestBase() {

    private val objectMapper = ObjectMapper()

    private fun json(text: String): ObjectNode = objectMapper.readValue(text, ObjectNode::class.java)

    private val contract = """
        {
          "type": "object",
          "properties": {
            "customer": {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "phone": { "type": "string" },
                "fax": { "type": "string" }
              },
              "required": ["name"]
            },
            "invoiceDate": { "type": "string", "format": "date" }
          },
          "required": ["customer", "invoiceDate"]
        }
    """

    /** A text block that prints `customer.name` and `customer.phone` — but not `customer.fax`. */
    private fun templateReadingNameAndPhone(): TemplateDocument {
        val text = Node(
            id = "text",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "expression", "attrs" to mapOf("expression" to "customer.name")),
                                mapOf("type" to "expression", "attrs" to mapOf("expression" to "customer.phone")),
                            ),
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

    private fun withPublishedTemplate(block: ThenScope.(DocumentSetup) -> Unit) = scenario {
        given {
            val tenant = tenant("Analyze Tenant")
            val template = template(tenant.id, "Invoice")
            val templateId = TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))
            val variant = variant(templateId, "Default")
            val variantId = VariantId(variant.id, templateId)
            UpdateContractVersion(
                templateId = templateId,
                dataModel = json(contract),
                dataExamples = listOf(
                    DataExample("example-1", "Example 1", json("""{"customer": {"name": "Ada", "phone": "555"}, "invoiceDate": "2026-09-22"}""")),
                ),
            ).let(::execute)
            val draft = version(variantId, templateReadingNameAndPhone())
            val published = execute(PublishVersion(VersionId(draft.id, variantId)))!!
            DocumentSetup(tenant, template, variant, published)
        }.whenever { it }.then { setup, _ -> block(setup) }
    }

    private fun DocumentSetup.analyze(data: ObjectNode) = AnalyzeTemplateData(
        tenantKey = tenant.id,
        catalogKey = CatalogKey.DEFAULT,
        templateId = template.id,
        variantId = variant.id,
        data = data,
    )

    private fun DocumentSetup.preview(data: ObjectNode) = PreviewDocument(
        tenantId = tenant.id,
        catalogKey = CatalogKey.DEFAULT,
        templateId = template.id,
        variantId = variant.id,
        data = data,
    )

    @Test
    fun `reports missing required fields and the optional fields the published template reads`() = withPublishedTemplate { setup ->
        val analysis = query(setup.analyze(json("""{"customer": {}}""")))

        assertThat(analysis.valid).isFalse()
        // fax is optional and not printed, so it is not asked for.
        assertThat(analysis.missingFields.map { it.path to it.required }).containsExactly(
            "/customer/name" to true,
            "/customer/phone" to false,
            "/invoiceDate" to true,
        )
        assertThat(analysis.missingFields.last().schema.get("format").asString()).isEqualTo("date")
    }

    @Test
    fun `preview rejects the same data with the same analysis`() = withPublishedTemplate { setup ->
        val data = json("""{"customer": {"phone": 555}}""")

        val error = assertThrows<TemplateDataInvalidException> { query(setup.preview(data)) }

        assertThat(error.analysis).isEqualTo(query(setup.analyze(data)))
        assertThat(error.analysis.invalidFields.map { it.path to it.keyword }).containsExactly("/customer/phone" to "type")
        assertThat(error.message).startsWith("Data validation failed: ")
            .contains("/customer/name: is required", "/invoiceDate: is required", "/customer/phone")
    }

    @Test
    fun `an absent optional field is reported but does not stop the preview`() = withPublishedTemplate { setup ->
        val data = json("""{"customer": {"name": "Ada"}, "invoiceDate": "2026-09-22"}""")

        val analysis = query(setup.analyze(data))

        assertThat(analysis.valid).isTrue()
        assertThat(analysis.missingFields.map { it.path to it.required }).containsExactly("/customer/phone" to false)
        assertThat(query(setup.preview(data))).isNotEmpty()
    }

    @Test
    fun `a template viewer may analyze data but not render a preview`() = withPublishedTemplate { setup ->
        val viewer = EpistolaPrincipal(
            userId = UserKey.of(UUID.randomUUID()),
            externalId = "viewer-${UUID.randomUUID()}",
            email = "viewer@analyze.example",
            displayName = "Viewer",
            tenantMemberships = mapOf(setup.tenant.id to setOf(TenantRole.CONTENT_VIEWER)),
            currentTenantId = setup.tenant.id,
        )
        val data = json("""{"customer": {}}""")

        val analysis = runAs(viewer) { setup.analyze(data).query() }

        assertThat(analysis.missingFields).isNotEmpty()
        assertThrows<PermissionDeniedException> { runAs(viewer) { setup.preview(data).query() } }
    }

    @Test
    fun `no data checks the contract's first example, like preview`() = withPublishedTemplate { setup ->
        val analysis = query(setup.analyze(objectMapper.createObjectNode()))

        assertThat(analysis.valid).isTrue()
        assertThat(analysis.missingFields).isEmpty()
    }
}
