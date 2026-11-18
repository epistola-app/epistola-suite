// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.generation.RenderCulture
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.xmp.XMPMeta
import com.itextpdf.kernel.xmp.XMPMetaFactory
import com.itextpdf.kernel.xmp.properties.XMPPropertyInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A document's generation context is written to its XMP, read back here as an integrator would
 * read it. Whether the XMP still conforms to PDF/A-2b and PDF/UA-1 is [PdfConformanceTest]'s
 * business, which renders its whole corpus with a context.
 */
class GenerationContextXmpTest {

    private val renderer = DirectPdfRenderer()
    private val ns = GenerationContextXmp.NAMESPACE

    private val context = GenerationContext(
        tenant = "acme",
        registry = "local",
        namespace = "acme",
        catalog = "letters",
        template = "invoice",
        variant = "nl",
        release = "1.4.0",
        catalogVersion = "0199a3c2-7f00-7000-8000-000000000001",
        environment = "production",
        request = "0199a3c2-7f00-7000-8000-000000000002",
        batch = "0199a3c2-7f00-7000-8000-000000000003",
    )

    private val clock = Clock.fixed(Instant.parse("2026-11-17T09:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `the context, the render time, the locale and the engine are written to the XMP`() {
        for (pdfa in listOf(true, false)) {
            val xmp = xmpOf(render(singlePass, context, pdfa))!!

            assertEquals("acme", xmp.getPropertyString(ns, "tenant"), "pdfa=$pdfa")
            assertEquals("local", xmp.getPropertyString(ns, "registry"))
            assertEquals("acme", xmp.getPropertyString(ns, "namespace"))
            assertEquals("letters", xmp.getPropertyString(ns, "catalog"))
            assertEquals("invoice", xmp.getPropertyString(ns, "template"))
            assertEquals("nl", xmp.getPropertyString(ns, "variant"))
            assertEquals("1.4.0", xmp.getPropertyString(ns, "release"))
            assertEquals(context.catalogVersion, xmp.getPropertyString(ns, "catalogVersion"))
            assertEquals("production", xmp.getPropertyString(ns, "environment"))
            assertEquals(context.request, xmp.getPropertyString(ns, "request"))
            assertEquals(context.batch, xmp.getPropertyString(ns, "batch"))
            assertEquals(false, xmp.getPropertyBoolean(ns, "preview"))
            assertEquals("de-DE", xmp.getPropertyString(ns, "locale"))
            assertEquals("epistola-gen-test", xmp.getPropertyString(ns, "engine"))
            assertEquals("2026-11-17T09:30:00Z", xmp.getPropertyString(ns, "renderedAt"))
        }
    }

    @Test
    fun `the render time is the one the document shows as sys render time`() {
        val pdf = render(singlePass, context, pdfa = true)
        val renderedAt = xmpOf(pdf)!!.getPropertyString(ns, "renderedAt")
        assertTrue(renderedAt in PdfContentExtractor.extract(pdf), "the body shows $renderedAt")
    }

    @Test
    fun `a document rendered in two passes for its page total carries the context too`() {
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(twoPass), "the page total takes two passes")
        val xmp = xmpOf(render(twoPass, context, pdfa = true))!!
        assertEquals("invoice", xmp.getPropertyString(ns, "template"))
        assertEquals("de-DE", xmp.getPropertyString(ns, "locale"))
        assertEquals("2026-11-17T09:30:00Z", xmp.getPropertyString(ns, "renderedAt"))
    }

    @Test
    fun `a draft render names the draft, and a preview says so, without a request`() {
        val draft = context.copy(release = null, draft = "d-42", draftLabel = "Spring campaign", request = null, batch = null, preview = true)
        val xmp = xmpOf(render(singlePass, draft, pdfa = false))!!

        assertEquals("d-42", xmp.getPropertyString(ns, "draft"))
        assertEquals("Spring campaign", xmp.getPropertyString(ns, "draftLabel"))
        assertEquals(true, xmp.getPropertyBoolean(ns, "preview"))
        assertNull(xmp.getProperty(ns, "release"), "an absent value is left out, not written empty")
        assertNull(xmp.getProperty(ns, "request"))
        assertNull(xmp.getProperty(ns, "batch"))
    }

    @Test
    fun `without a context nothing of it is written`() {
        val withPdfA = xmpOf(render(singlePass, context = null, pdfa = true))!!
        assertFalse(propertiesIn(withPdfA).any { it.namespace == ns }, "no generation context in a PDF/A document's XMP")
        assertNull(xmpOf(render(singlePass, context = null, pdfa = false)), "no XMP at all, as before")
    }

    @Test
    fun `every property written is described in the extension schema PDF-A requires`() {
        val xmp = xmpOf(render(singlePass, context.copy(draft = "d-1", draftLabel = "x"), pdfa = true))!!
        val written = propertiesIn(xmp).filter { it.namespace == ns && it.path?.startsWith("$PREFIX:") == true }
            .map { it.path.removePrefix("$PREFIX:") }
            .toSet()
        val described = propertiesIn(xmp)
            .filter { it.namespace == "http://www.aiim.org/pdfa/ns/property#" && it.path?.endsWith("pdfaProperty:name") == true }
            .map { it.value }
            .toSet()

        assertEquals(17, written.size, "every property of the namespace is written in this render: $written")
        assertEquals(emptySet(), written - described, "written but not described")
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun render(document: TemplateDocument, context: GenerationContext?, pdfa: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        renderer.render(
            document = document,
            data = emptyMap(),
            outputStream = out,
            metadata = PdfMetadata(title = "Invoice", author = "Acme", engineVersion = "epistola-gen-test", generationContext = context),
            pdfaCompliant = pdfa,
            culture = RenderCulture(locale = Locale.forLanguageTag("de-DE")),
            clock = clock,
        )
        return out.toByteArray()
    }

    private fun xmpOf(pdf: ByteArray): XMPMeta? = PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { doc ->
        doc.xmpMetadataBytes?.let { XMPMetaFactory.parseFromBuffer(it) }
    }

    private fun propertiesIn(xmp: XMPMeta): List<XMPPropertyInfo> = buildList {
        val iterator = xmp.iterator()
        while (iterator.hasNext()) add(iterator.next() as XMPPropertyInfo)
    }

    private fun documentShowing(vararg expressions: String): TemplateDocument {
        val text = Node(
            id = "n-text",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to expressions.flatMap {
                                listOf(mapOf("type" to "text", "text" to " "), mapOf("type" to "expression", "attrs" to mapOf("expression" to it)))
                            },
                        ),
                    ),
                ),
            ),
        )
        return TemplateDocument(
            root = "n-root",
            nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root")), text.id to text),
            slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children", children = listOf(text.id))),
        )
    }

    private val singlePass = documentShowing("sys.render.time")
    private val twoPass = documentShowing("sys.render.time", "sys.pages.total")

    private companion object {
        const val PREFIX = GenerationContextXmp.PREFIX
    }
}
