// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.testing.IntegrationTestBase
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * The demo's stencil showcase really does demonstrate stencils by reference with lexical scope
 * (contract ADR 0006): a stencil inside a stencil, a placeholder that passes its stencil's
 * parameters to the template's fill, and a stencil that reads the template's data.
 */
class DemoStencilShowcaseIntegrationTest : IntegrationTestBase() {
    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"
    private val demoCatalog = CatalogKey.of("epistola-demo")
    private val showcase = TemplateKey.of("stencil-showcase")

    private fun previewText(): String {
        val tenant = createTenant("Demo stencil showcase").id
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant, sourceUrl = demoUrl).execute() }
        val data = withMediator { GetDataContract(TemplateId(showcase, CatalogId(demoCatalog, TenantId(tenant)))).query() }!!
            .dataExamples.single { it.name == "Default" }.data
        val pdf = withMediator { PreviewDocument(tenantId = tenant, catalogKey = demoCatalog, templateId = showcase, data = data).query() }
        return PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { doc ->
            (1..doc.numberOfPages).joinToString("\n") { PdfTextExtractor.getTextFromPage(doc.getPage(it)) }
        }.replace(Regex("\\s+"), " ") // lines wrap where the layout puts them
    }

    @Test
    fun `the shell's letterhead, the passed parameter in the fill and the template data in the address stencil all render`() {
        val text = previewText()

        // The letterhead stencil inside the shell, bound to the shell's parameter.
        assertThat(text).contains("Letterhead from the Letterhead stencil").contains("Globex Corporation · page 1")
        // The template's fill reads the parameter the shell's placeholder passes, under `shell`.
        assertThat(text).contains("This letter is laid out by the Letter Shell stencil of Globex Corporation.")
        // The address stencil reads the template's data, so it sees the customer.
        assertThat(text).contains("Ada Lovelace").contains("Keizersgracht 123").contains("1015 CJ Amsterdam")
        assertThat(text).contains("Dear Ada Lovelace,")
    }
}
