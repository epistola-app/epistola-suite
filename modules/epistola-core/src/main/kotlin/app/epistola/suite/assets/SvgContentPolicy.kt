// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.validation.ValidationException
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

/**
 * What an uploaded SVG may contain. An SVG is a document, not a picture: it can script, embed
 * HTML, and point at other resources. Epistola renders SVG assets into PDFs and serves them to
 * browsers, and needs none of that, so an SVG is refused unless it is self-contained and inert.
 *
 * Refused:
 * - a DOCTYPE (and with it every entity declaration), or XML that is not well formed;
 * - `<script>`, `<foreignObject>` and the other elements that run code or embed documents;
 * - any `on*` event-handler attribute;
 * - any `href` / `xlink:href` that is not a same-document `#fragment` or a `data:` URI;
 * - an `xml-stylesheet` processing instruction;
 * - CSS in `<style>` or a `style` attribute with an `@import` or a backslash escape (escaped CSS
 *   cannot be checked reliably);
 * - a `url(...)` in CSS or any attribute (`fill`, `filter`, `mask`…) that is not a `#fragment` or
 *   `data:` URI.
 *
 * Enforced where every surface writes an asset ([app.epistola.suite.assets.commands.UploadAsset]
 * and the catalog `ImportAsset`), so the web UI, REST, MCP and catalog import share it. The PDF
 * renderer refuses external resources independently, which also covers SVGs stored before this
 * policy existed.
 */
object SvgContentPolicy {
    const val FIELD = "file"

    /**
     * `Content-Security-Policy` for every response that serves an SVG asset. Opened directly, an
     * SVG is a document in the application's origin; this keeps it a picture: no scripts, no
     * fetches, no forms or navigation, inline styles and `data:` images only. Sent together with
     * `X-Content-Type-Options: nosniff`.
     */
    const val SERVING_CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; sandbox"

    private val forbiddenElements = setOf("script", "foreignobject", "handler", "iframe", "embed", "object")

    /** For an SVG [mediaType], throws a [ValidationException] when [content] breaks the policy. */
    fun requireSafe(mediaType: AssetMediaType, content: ByteArray) {
        if (isSvg(mediaType)) requireSafe(content)
    }

    fun isSvg(mediaType: AssetMediaType): Boolean = mediaType.mimeType.substringBefore(';').trim().equals(AssetMediaType.SVG.mimeType, ignoreCase = true)

    /** Throws a [ValidationException] when [content] breaks the policy. */
    fun requireSafe(content: ByteArray) {
        violation(content)?.let { throw ValidationException(FIELD, "SVG not accepted: $it.") }
    }

    /** The first reason [content] breaks the policy, or null when it is acceptable. */
    fun violation(content: ByteArray): String? {
        val handler = PolicyHandler()
        try {
            parserFactory().newSAXParser().apply {
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }.parse(InputSource(ByteArrayInputStream(content)), handler)
        } catch (e: Rejected) {
            return e.message
        } catch (e: SAXParseException) {
            return if (e.message?.contains("DOCTYPE", ignoreCase = true) == true) {
                "it contains a DOCTYPE declaration"
            } else {
                "it is not well-formed XML"
            }
        }
        return null
    }

    private fun parserFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

    private class Rejected(reason: String) : RuntimeException(reason, null, false, false)

    private class PolicyHandler : DefaultHandler() {
        private var styleDepth = 0
        private val styleText = StringBuilder()

        override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
            val name = localName.ifEmpty { qName.substringAfter(':') }.lowercase()
            if (name in forbiddenElements) throw Rejected("it contains a <$name> element")
            if (name == "style") styleDepth++
            for (i in 0 until attributes.length) {
                val attribute = attributes.getLocalName(i).ifEmpty { attributes.getQName(i).substringAfter(':') }
                val value = attributes.getValue(i)
                if (attribute.startsWith("on", ignoreCase = true)) {
                    throw Rejected("it contains an event-handler attribute ($attribute)")
                }
                if (attribute.equals("href", ignoreCase = true) && !isInternalReference(value)) {
                    throw Rejected("it references an external resource ($attribute)")
                }
                // Only `style` holds a stylesheet; elsewhere just url() matters (fill, filter, mask…),
                // and a stray backslash in, say, an editor's export path is harmless.
                val css = if (attribute.equals("style", ignoreCase = true)) cssViolation(value) else urlViolation(value)
                css?.let { throw Rejected("the $attribute attribute $it") }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (styleDepth > 0) styleText.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String, qName: String) {
            val name = localName.ifEmpty { qName.substringAfter(':') }.lowercase()
            if (name == "style" && styleDepth > 0 && --styleDepth == 0) {
                cssViolation(styleText.toString())?.let { throw Rejected("a <style> element $it") }
                styleText.setLength(0)
            }
        }

        override fun processingInstruction(target: String, data: String?) {
            if (target.equals("xml-stylesheet", ignoreCase = true)) {
                throw Rejected("it contains an xml-stylesheet instruction")
            }
        }

        // Never resolve anything, even if a parser ignores the features above.
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource(StringReader(""))
    }

    private val cssComment = Regex("""/\*.*?(\*/|$)""", RegexOption.DOT_MATCHES_ALL)
    private val cssImport = Regex("""@\s*import""", RegexOption.IGNORE_CASE)
    private val cssUrl = Regex("""url\(\s*(['"]?)\s*([^'")]*)""", RegexOption.IGNORE_CASE)

    private fun cssViolation(css: String): String? {
        val normalised = cssComment.replace(css, "")
        if ('\\' in normalised) return "contains escaped CSS"
        if (cssImport.containsMatchIn(normalised)) return "contains a CSS @import"
        return urlViolation(normalised)
    }

    private fun urlViolation(value: String): String? = if (cssUrl.findAll(value).any { !isInternalReference(it.groupValues[2]) }) {
        "references an external resource with url()"
    } else {
        null
    }

    private fun isInternalReference(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("#") || trimmed.startsWith("data:", ignoreCase = true)
    }
}
