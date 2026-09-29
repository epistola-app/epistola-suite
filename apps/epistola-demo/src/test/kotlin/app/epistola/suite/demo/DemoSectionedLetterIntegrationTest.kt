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
import app.epistola.suite.common.ids.TenantKey
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
 * The demo's sectioned and batch letters really do demonstrate page headers and footers placed
 * anywhere (#1020): install the bundled catalog, preview each example, and read each page's bands.
 * It fails if the letterhead stencil stops carrying its header and footer, or if a section, a
 * conditional or a loop iteration loses its own.
 */
class DemoSectionedLetterIntegrationTest : IntegrationTestBase() {
    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"
    private val demoCatalog = CatalogKey.of("epistola-demo")

    /** Installs the demo catalog and returns the text of every page of [template]'s [example]. */
    private fun previewPages(template: String, example: String): List<String> {
        val tenant = createTenant("Demo $template $example")
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute() }
        return previewPages(tenant.id, TemplateKey.of(template), example)
    }

    private fun previewPages(tenantKey: TenantKey, template: TemplateKey, example: String): List<String> {
        val data = withMediator {
            GetLatestContractVersion(TemplateId(template, CatalogId(demoCatalog, TenantId(tenantKey)))).query()
        }!!.dataExamples.single { it.name == example }.data
        val pdf = withMediator {
            PreviewDocument(tenantId = tenantKey, catalogKey = demoCatalog, templateId = template, data = data).query()
        }
        return PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { doc ->
            (1..doc.numberOfPages).map { PdfTextExtractor.getTextFromPage(doc.getPage(it)) }
        }
    }

    @Test
    fun `each section of the sectioned letter gets its own header and footer`() {
        val pages = previewPages("sectioned-letter", "Default")

        val letterPages = pages.indices.filter { pages[it].contains("Paragraph 1.") || pages[it].contains("Paragraph 18.") }
        val termsStart = pages.indexOfFirst { it.contains("Article 1.") }
        val appendixStart = pages.indexOfFirst { it.contains("Figure 1.") }
        assertThat(letterPages).describedAs("the letter should run over more than one page").hasSizeGreaterThan(1)
        assertThat(termsStart).isGreaterThan(letterPages.last())

        // Cover: the conditional header applies, and the empty footer keeps the letterhead's footer off it.
        assertThat(pages[0]).contains("Annual review").contains("CONFIDENTIAL").doesNotContain("Globex Corporation · page")
        // The letter: the letterhead stencil's header and footer, with the stencil's parameter.
        letterPages.forEach { page ->
            assertThat(pages[page])
                .contains("Letterhead from the Letterhead stencil")
                .contains("Globex Corporation · page ${page + 1} of ${pages.size}")
                .doesNotContain("CONFIDENTIAL")
        }
        // Terms: their own header from the page the section starts on, and a first-page footer.
        assertThat(pages[termsStart])
            .contains("Terms and conditions")
            .contains("Please sign and return the first page")
            .doesNotContain("Terms and appendix · page")
            .doesNotContain("Letterhead from")
        // The running footer covers the rest of the section.
        pages.drop(termsStart + 1).forEach { assertThat(it).contains("Terms and appendix · page").doesNotContain("Please sign") }
        // The appendix header comes after content, so it takes over only from the next page.
        assertThat(pages[appendixStart]).contains("Terms and conditions").doesNotContain("Appendix: figures")
        assertThat(pages.drop(appendixStart + 1)).isNotEmpty.allSatisfy { assertThat(it).contains("Appendix: figures") }
    }

    @Test
    fun `the cover header applies only when the data marks the review confidential`() {
        val pages = previewPages("sectioned-letter", "Not confidential")

        assertThat(pages[0]).contains("Annual review").doesNotContain("CONFIDENTIAL")
    }

    @Test
    fun `every letter in the batch gets its own header and footer, with no blank page`() {
        val pages = previewPages("batch-letters", "Three letters")

        assertThat(pages).hasSize(3)
        listOf("Alice de Vries" to "GX-1001", "Bram Jansen" to "GX-1002", "Chantal Bakker" to "GX-1003")
            .forEachIndexed { index, (name, reference) ->
                // Text extraction breaks lines where the page wraps them.
                assertThat(pages[index].replace(Regex("\\s+"), " "))
                    .contains("Globex Corporation · letter for $name")
                    .contains("Dear $name,")
                    .contains("Reference $reference · page ${index + 1}")
            }
    }
}
