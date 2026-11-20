// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.queries.AnalyzeTemplateData
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream

/**
 * The demo's objection decision demonstrates interactive generation. A caller that sends its data
 * before the decision is written — the example without the motivation and the decision date — lacks
 * exactly those two required fields, so `trigger: incomplete` asks a person for them; and the letter
 * renders what the person fills in. (A catalog's data examples must be valid, so the example itself is
 * complete.) It fails if the contract starts asking for more or less than those two, or if the
 * template stops rendering what the form asks for.
 */
class DemoObjectionDecisionIntegrationTest : IntegrationTestBase() {
    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"
    private val demoCatalog = CatalogKey.of("epistola-demo")
    private val template = TemplateKey.of("objection-decision")

    private fun installed(): TenantKey {
        val tenant = createTenant("Demo objection decision")
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute() }
        return tenant.id
    }

    private fun example(tenant: TenantKey): ObjectNode = withMediator {
        GetDataContract(TemplateId(template, testCatalog(TenantId(tenant), demoCatalog)), catalogVersion = null).query()
    }!!.dataExamples.single().data

    @Test
    fun `sent before the decision is written, the data lacks exactly the motivation and the decision date`() {
        val tenant = installed()
        val beforeTheDecision = example(tenant).deepCopy().apply { remove(listOf("motivation", "decisionDate")) }

        val analysis = withMediator { AnalyzeTemplateData(testCatalog(tenant, demoCatalog), template, beforeTheDecision).query() }

        assertThat(analysis.valid).isFalse()
        assertThat(analysis.invalidFields).isEmpty()
        assertThat(analysis.missingFields.filter { it.required }.map { it.path }).containsExactlyInAnyOrder("/motivation", "/decisionDate")
    }

    @Test
    fun `completed by a person, the letter renders what they filled in`() {
        val tenant = installed()
        val completed = example(tenant).deepCopy().apply {
            put("motivation", "Measured again, the floor area is 12 square metres smaller.")
            put("decisionDate", "2026-11-20")
        }

        val pdf = withMediator { PreviewDocument(catalog = testCatalog(tenant, demoCatalog), templateId = template, data = completed, catalogVersion = null).query() }
        val text = PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { doc ->
            (1..doc.numberOfPages).joinToString("\n") { PdfTextExtractor.getTextFromPage(doc.getPage(it)) }
        }.replace(Regex("\\s+"), " ")

        assertThat(text)
            .contains("Decision on your objection")
            .contains("Case reference: BZ-2026-0142")
            .contains("Dear Jane Jansen,")
            .contains("your objection is partly upheld.")
            .contains("The assessment used the wrong floor area; The notice arrived after the deadline")
            .contains("12 square metres smaller.")
            // The preview's watermark text can land inside a sentence, so the appeal line is checked in parts.
            .contains("You can appeal against this")
            .contains("within six weeks of")
            .doesNotContain("This decision is final.")
    }
}
