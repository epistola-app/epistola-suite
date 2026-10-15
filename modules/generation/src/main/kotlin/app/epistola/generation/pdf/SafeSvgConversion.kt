// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.layout.element.Image
import com.itextpdf.styledxmlparser.jsoup.nodes.DataNode
import com.itextpdf.styledxmlparser.jsoup.nodes.Document
import com.itextpdf.styledxmlparser.jsoup.nodes.TextNode
import com.itextpdf.styledxmlparser.node.impl.jsoup.JsoupXmlParser
import com.itextpdf.styledxmlparser.node.impl.jsoup.node.JsoupDocumentNode
import com.itextpdf.styledxmlparser.resolver.font.BasicFontProvider
import com.itextpdf.styledxmlparser.resolver.resource.IResourceRetriever
import com.itextpdf.svg.converter.SvgConverter
import com.itextpdf.svg.processors.impl.SvgConverterProperties
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL

/**
 * The only way SVG assets are converted for a PDF. Every iText `SvgConverter` call must go
 * through here, so an SVG can never make the renderer reach outside its own bytes.
 *
 * iText's defaults resolve `<image href>`, `<link rel="stylesheet">`, CSS `@import` and
 * `@font-face` URLs against the working directory and fetch them over any URL scheme — an uploaded
 * SVG could make the server issue HTTP requests or embed local image files into a generated PDF.
 * This closes that:
 *
 * - the [resource retriever][NoExternalResourceRetriever] refuses every URL. Base64 `data:` URIs
 *   still work: iText decodes those itself and never hands them to the retriever;
 * - the base URI is a fixed, unresolvable placeholder, so relative references never resolve
 *   against the working directory;
 * - the font provider knows the standard PDF fonts only (no system fonts) — the same set SVG
 *   text has always rendered with — and `@font-face` sources go through the refusing retriever;
 * - `@import` rules are removed from `<style>` elements before iText sees them, because iText's
 *   CSS parser fetches imports with its own built-in retriever, bypassing the one configured here.
 */
object SafeSvgConversion {
    private val log = LoggerFactory.getLogger(SafeSvgConversion::class.java)

    /** Never contacted: the `.invalid` TLD cannot resolve and the retriever refuses it anyway. */
    internal const val BASE_URI = "https://svg.invalid/"

    /** Fresh properties per conversion: iText's font provider and resolver cache are stateful. */
    fun properties(): SvgConverterProperties = SvgConverterProperties()
        .setResourceRetriever(NoExternalResourceRetriever)
        .setBaseUri(BASE_URI)
        .setFontProvider(BasicFontProvider(true, false))

    fun convertToImage(svgBytes: ByteArray, pdfDocument: PdfDocument): Image = ByteArrayInputStream(withoutCssImports(svgBytes)).use { svgStream ->
        SvgConverter.convertToImage(svgStream, pdfDocument, properties())
    }

    /**
     * Returns [svgBytes] unchanged unless a `<style>` element holds an `@import`; then the SVG is
     * re-serialised with those rules removed. It parses with the same parser iText uses, so the
     * style text inspected here — after entity decoding and CDATA unwrapping — is exactly the text
     * iText's CSS parser would receive.
     */
    internal fun withoutCssImports(svgBytes: ByteArray): ByteArray {
        val document = parse(svgBytes)
        if (!stripImports(document)) return svgBytes
        log.warn("Removed CSS @import rules from an SVG asset before rendering")
        val rewritten = serialise(document)
        // The rewrite must hold up when iText parses it again; if anything still imports, refuse.
        check(!stripImports(parse(rewritten))) { "SVG still contains a CSS @import after sanitising" }
        return rewritten
    }

    private fun parse(svgBytes: ByteArray): Document = (JsoupXmlParser().parse(ByteArrayInputStream(svgBytes), null) as JsoupDocumentNode).document

    /** Removes `@import` rules from every `<style>` child iText reads; true when anything changed. */
    private fun stripImports(document: Document): Boolean {
        var changed = false
        for (element in document.allElements) {
            if (!element.tagName().substringAfter(':').equals("style", ignoreCase = true)) continue
            for (child in element.childNodes().toList()) {
                val css = when (child) {
                    is DataNode -> child.wholeData
                    is TextNode -> child.wholeText
                    else -> continue
                }
                if (!importPattern.containsMatchIn(normalised(css))) continue
                val cleaned = normalised(css).replace(importRule, "")
                // An import iText would still recognise after the rewrite (an escaped at-keyword,
                // say) is not worth parsing further: the whole block goes.
                val replacement = if (importPattern.containsMatchIn(cleaned)) "" else cleaned
                // A text node, not a data node: it serialises escaped, so `&#64;import` written
                // as `&amp;#64;import` cannot decode into a new import when iText re-parses it.
                child.replaceWith(TextNode(replacement))
                changed = true
            }
        }
        return changed
    }

    private fun serialise(document: Document): ByteArray {
        document.outputSettings().prettyPrint(false)
        return document.outerHtml().toByteArray(document.charset())
    }

    /** CSS comments carry no meaning, and iText's parser skips them even inside an at-keyword. */
    private fun normalised(css: String): String = css.replace(comment, "")

    private val comment = Regex("""/\*.*?(\*/|$)""", RegexOption.DOT_MATCHES_ALL)

    /** `@import`, in any case, and any at-keyword spelled with a CSS escape. */
    private val importPattern = Regex("""@\s*(\\|import)""", RegexOption.IGNORE_CASE)

    private val importRule = Regex("""@import\b[^;{}]*;?""", RegexOption.IGNORE_CASE)

    /**
     * Refuses every external resource an SVG references; returning null is iText's "not found".
     *
     * iText 9 deprecates this interface in favour of `com.itextpdf.io.resolver.resource.IResourceRetriever`,
     * but `SvgConverterProperties.setResourceRetriever` still takes only this one.
     */
    @Suppress("DEPRECATION")
    internal object NoExternalResourceRetriever : IResourceRetriever {
        override fun getInputStreamByUrl(url: URL): InputStream? = refuse(url)

        override fun getByteArrayByUrl(url: URL): ByteArray? = refuse(url)

        private fun <T> refuse(url: URL): T? {
            log.warn("Refused external resource referenced from an SVG asset: scheme={}", url.protocol)
            return null
        }
    }
}
