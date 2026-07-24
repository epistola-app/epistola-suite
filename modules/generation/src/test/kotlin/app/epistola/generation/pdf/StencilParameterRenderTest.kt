// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * End-to-end render tests proving parameter bindings actually flow through
 * to the rendered PDF. Each test:
 *   1. Constructs a stencil node with a parameterSchemaSnapshot + parameterBindings.
 *   2. Places a `{{ params.* }}` expression in the stencil's body content.
 *   3. Renders with DirectPdfRenderer wired to a snapshot-reading schema provider
 *      (the same provider the production Spring config registers via
 *      StencilNodeParameterSchemaProviderConfig).
 *   4. Extracts text from the PDF and asserts the bound value appears.
 *
 * These cover the gap where the per-component unit tests pass but the chain
 * could still fail in integration (e.g. if StencilNodeRenderer dropped the
 * scope, or the renderer skipped parameterScopes when merging effectiveData).
 */
class StencilParameterRenderTest {
    /**
     * Construct a renderer wired with the stencil-snapshot schema provider —
     * this mirrors what `PdfRendererConfiguration` does in production.
     */
    private val renderer = DirectPdfRenderer(
        parameterSchemaProvider = { node, _ ->
            @Suppress("UNCHECKED_CAST")
            node.props?.get("parameterSchemaSnapshot") as? Map<String, Any?>
        },
    )

    private fun renderToText(document: TemplateDocument, data: Map<String, Any?>): String {
        val output = ByteArrayOutputStream()
        renderer.render(document, data, output)
        val pdfBytes = output.toByteArray()
        check(pdfBytes.isNotEmpty()) { "renderer produced no output" }
        return PdfContentExtractor.extract(pdfBytes)
    }

    /**
     * Stencil whose body renders `{{ params.<paramName> }}` against a snapshot
     * declaring that parameter as a required string with the given binding.
     */
    private fun docWithParam(
        paramName: String,
        binding: String,
        defaultValue: String? = null,
        alias: String = "params",
    ): TemplateDocument {
        val schemaProperty = mutableMapOf<String, Any?>("type" to "string")
        if (defaultValue != null) schemaProperty["default"] = defaultValue
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(paramName to schemaProperty),
            "required" to listOf(paramName),
        )
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "stencil1" to Node(
                    id = "stencil1",
                    type = "stencil",
                    slots = listOf("stencil1-slot"),
                    props = mapOf(
                        "stencilId" to "letter",
                        "version" to 1,
                        "parameterSchemaSnapshot" to schema,
                        "parameterBindings" to mapOf(paramName to binding),
                        "paramsAlias" to alias,
                    ),
                ),
                "body" to Node(
                    id = "body",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf(
                                            "type" to "expression",
                                            "attrs" to mapOf("expression" to "$alias.$paramName"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil1")),
                "stencil1-slot" to Slot("stencil1-slot", "stencil1", "children", listOf("body")),
            ),
        )
    }

    @Test
    fun `simple path binding to user data renders the resolved value`() {
        val doc = docWithParam("recipientName", binding = "customer.name")
        val text = renderToText(doc, data = mapOf("customer" to mapOf("name" to "Alice")))
        assertContains(text, "Alice")
    }

    @Test
    fun `JSONata literal binding renders the literal`() {
        val doc = docWithParam("greeting", binding = "'hello world'")
        val text = renderToText(doc, data = emptyMap())
        assertContains(text, "hello world")
    }

    @Test
    fun `string concatenation binding renders the concatenated value`() {
        val doc = docWithParam("greeting", binding = "first & ' ' & last")
        val text = renderToText(doc, data = mapOf("first" to "Hello", "last" to "World"))
        assertContains(text, "Hello World")
    }

    @Test
    fun `unbound parameter falls back to schema default`() {
        // The doc declares the param with a default but supplies an empty
        // expression — the binding-empty validator would normally reject this
        // upstream, but the renderer itself should fall through to the default.
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "stencil1" to Node(
                    id = "stencil1",
                    type = "stencil",
                    slots = listOf("stencil1-slot"),
                    props = mapOf(
                        "stencilId" to "letter",
                        "version" to 1,
                        "parameterSchemaSnapshot" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "name" to mapOf("type" to "string", "default" to "Anonymous"),
                            ),
                        ),
                    ),
                ),
                "body" to Node(
                    id = "body",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf(
                                            "type" to "expression",
                                            "attrs" to mapOf("expression" to "params.name"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil1")),
                "stencil1-slot" to Slot("stencil1-slot", "stencil1", "children", listOf("body")),
            ),
        )

        val text = renderToText(doc, data = emptyMap())
        assertContains(text, "Anonymous")
    }

    @Test
    fun `paramsAlias overrides the default 'params' namespace`() {
        // Binding is referenced inside the stencil via 'letter.title' instead of 'params.title'.
        val doc = docWithParam(
            "title",
            binding = "'Acme Inc'",
            alias = "letter",
        )
        val text = renderToText(doc, data = emptyMap())
        assertContains(text, "Acme Inc")
    }

    @Test
    fun `binding to sys-pages-total resolves via two-pass rendering`() {
        // TwoPassAnalyzer must detect this binding and trigger two-pass; otherwise
        // sys.pages.total would be undefined at render time.
        val doc = docWithParam("totalPages", binding = "sys.pages.total")
        val text = renderToText(doc, data = emptyMap())
        // Single-page document → total = 1.
        assertContains(text, "1")
    }
}
