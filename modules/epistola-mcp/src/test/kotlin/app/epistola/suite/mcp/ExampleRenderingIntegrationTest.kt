// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.generation.testing.RegistryExamples
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Smoke test that every hand-curated example actually renders to a non-empty
 * PDF when wrapped in a TemplateDocument and fed through `PreviewVariant`.
 *
 * Goes one level beyond the structural validation in
 * `ComponentTypesIntegrationTest` and `registry-examples.test.ts`: it executes
 * the real generation pipeline (theme cascade, expression evaluation,
 * iText rendering), so authoring mistakes that pass JSON-schema validation —
 * a malformed JSONata expression, an inspector prop with the wrong type,
 * an asset reference to nothing — surface here as a render failure.
 *
 * The examples and their sample data come from `RegistryExamples` (test fixtures of
 * `modules:generation`), shared with the veraPDF `PdfConformanceTest`.
 *
 * One template is created per test run; every example reuses it via the
 * `templateModel` override on `PreviewVariant`. Each render is a few ms;
 * the entire test typically runs in < 1s on top of the shared Spring
 * context boot.
 */
@Timeout(60)
class ExampleRenderingIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `every component example renders to a non-empty PDF`() {
        val tenant = createTenant("Examples Render Tenant")
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val variantId = VariantKey.INITIAL

        withMediator {
            CreateDocumentTemplate(id = templateId, name = "Examples Test Template", catalogVersion = null).execute()
        }

        val data: ObjectNode = objectMapper.valueToTree(RegistryExamples.sampleData)
        val failures = mutableListOf<String>()
        var rendered = 0

        for (example in RegistryExamples.all()) {
            val templateModel = example.document
            try {
                val pdfBytes = withMediator {
                    PreviewVariant(
                        catalog = testCatalog(tenant.id, CatalogKey.DEFAULT),
                        templateId = templateId.key,
                        variantId = variantId,
                        data = data,
                        templateModel = templateModel,
                        catalogVersion = null,
                    ).query()
                }
                if (pdfBytes.isEmpty()) {
                    failures += "${example.id}: empty PDF"
                } else {
                    rendered++
                }
            } catch (e: Exception) {
                failures += "${example.id}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        assertThat(failures)
            .withFailMessage("rendering failures (rendered ok=$rendered):\n${failures.joinToString("\n")}")
            .isEmpty()
        assertThat(rendered).isGreaterThan(0)
    }
}
