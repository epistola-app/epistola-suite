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
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.testing.IntegrationTestBase
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * The demo's sectioned letter really does demonstrate page headers and footers placed anywhere
 * (#1020): install the bundled catalog, preview its example, and read each page's bands. It fails
 * if the letterhead stencil stops carrying its header and footer, or if a section loses its own.
 */
class DemoSectionedLetterIntegrationTest : IntegrationTestBase() {
    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"
    private val demoCatalog = CatalogKey.of("epistola-demo")
    private val letter = TemplateKey.of("sectioned-letter")

    @Test
    fun `each section of the sectioned letter gets its own header and footer`() {
        val tenant = createTenant("Demo Sectioned Letter")
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute() }
        val example = withMediator {
            GetLatestContractVersion(TemplateId(letter, CatalogId(demoCatalog, TenantId(tenant.id)))).query()
        }!!.dataExamples.single()

        val pdf = withMediator {
            PreviewDocument(tenantId = tenant.id, catalogKey = demoCatalog, templateId = letter, data = example.data).query()
        }
        val pages = PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { doc ->
            (1..doc.numberOfPages).map { PdfTextExtractor.getTextFromPage(doc.getPage(it)) }
        }

        val letterPages = pages.indices.filter { pages[it].contains("Paragraph 1.") || pages[it].contains("Paragraph 18.") }
        val termsStart = pages.indexOfFirst { it.contains("Article 1.") }
        val appendixStart = pages.indexOfFirst { it.contains("Figure 1.") }
        assertThat(letterPages).describedAs("the letter should run over more than one page").hasSizeGreaterThan(1)
        assertThat(termsStart).isGreaterThan(letterPages.last())

        // Cover: no header, and its empty footer keeps the letterhead's footer off it.
        assertThat(pages[0]).contains("Annual review").doesNotContain("Globex Corporation · page")
        // The letter: the letterhead stencil's header and footer, with the stencil's parameter.
        letterPages.forEach { page ->
            assertThat(pages[page]).contains("Letterhead from the Letterhead stencil").contains("Globex Corporation · page ${page + 1} of ${pages.size}")
        }
        // Terms: their own header from the page the section starts on, and their own footer.
        assertThat(pages[termsStart]).contains("Terms and conditions").contains("Terms and appendix · page").doesNotContain("Letterhead from")
        // The appendix header comes after content, so it takes over only from the next page.
        assertThat(pages[appendixStart]).contains("Terms and conditions").doesNotContain("Appendix: figures")
        assertThat(pages.drop(appendixStart + 1)).isNotEmpty.allSatisfy { assertThat(it).contains("Appendix: figures") }
    }
}
