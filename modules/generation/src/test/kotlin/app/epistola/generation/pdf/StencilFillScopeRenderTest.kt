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
import kotlin.test.assertFalse

/**
 * A placeholder's fill reads the scope it was written in, not the stencil's: a template's fill sees
 * the template's data and never the stencil's `params`, while the stencil's own content (and a
 * placeholder's default) does see them. Rendered to PDF text end to end.
 */
class StencilFillScopeRenderTest {

    private val renderer = DirectPdfRenderer(
        parameterSchemaProvider = { node, _ ->
            @Suppress("UNCHECKED_CAST")
            node.props?.get("parameterSchemaSnapshot") as? Map<String, Any?>
        },
    )

    private fun render(document: TemplateDocument, data: Map<String, Any?>): String {
        val output = ByteArrayOutputStream()
        renderer.render(document, data, output)
        return PdfContentExtractor.extract(output.toByteArray())
    }

    private class Doc {
        val nodes = linkedMapOf<String, Node>()
        val slots = linkedMapOf<String, Slot>()

        fun node(id: String, type: String, props: Map<String, Any?>? = null, vararg slotChildren: Pair<String, List<String>>): String {
            val ids = slotChildren.map { (name, children) -> "$id/$name".also { slots[it] = Slot(it, id, name, children) } }
            nodes[id] = Node(id = id, type = type, slots = ids, props = props)
            return id
        }

        /** A text node rendering `label=<expression>`. */
        fun expr(id: String, label: String, expression: String) = node(
            id,
            "text",
            mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "$label="),
                                mapOf("type" to "expression", "attrs" to mapOf("expression" to expression)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        fun placeholder(id: String, name: String, default: List<String> = emptyList(), fill: List<String> = emptyList()) = node(id, "placeholder", mapOf("name" to name), "default" to default, "fill" to fill)

        fun stencil(id: String, key: String, param: String, binding: String, children: List<String>, extra: Map<String, Any?> = emptyMap()) = node(
            id,
            "stencil",
            mapOf(
                "stencilId" to key,
                "parameterSchemaSnapshot" to mapOf("type" to "object", "properties" to mapOf(param to mapOf("type" to "string"))),
                "parameterBindings" to mapOf(param to binding),
            ) + extra,
            "children" to children,
        )

        fun build(vararg rootChildren: String): TemplateDocument {
            node("root", "root", null, "children" to rootChildren.toList())
            return TemplateDocument(root = "root", nodes = nodes, slots = slots)
        }
    }

    private fun doc(content: Doc.() -> TemplateDocument) = Doc().content()

    /** A letterhead with parameter `name` bound to `'Stencil Name'`, and a `body` placeholder. */
    private fun template(fillExpression: String, extra: Map<String, Any?> = emptyMap()) = doc {
        expr("own", "own", "params.name")
        expr("default", "default", "params.name")
        expr("fill", "fill", fillExpression)
        placeholder("body", "body", default = emptyList(), fill = listOf("fill"))
        stencil("inst", "letterhead", "name", "'Stencil Name'", listOf("own", "body"), extra)
        build("inst")
    }

    @Test
    fun `the stencil's own content sees its params`() {
        val text = render(template("customer"), mapOf("customer" to "Jane"))
        assertContains(text, "own=Stencil Name")
    }

    @Test
    fun `a fill sees the template's data`() {
        val text = render(template("customer"), mapOf("customer" to "Jane"))
        assertContains(text, "fill=Jane")
    }

    @Test
    fun `a fill does not see the stencil's params`() {
        val text = render(template("params.name"), mapOf("customer" to "Jane"))
        assertContains(text, "own=Stencil Name")
        assertFalse(text.contains("fill=Stencil Name"), text)
    }

    @Test
    fun `a placeholder's default sees the stencil's params`() {
        val document = doc {
            expr("default", "default", "params.name")
            placeholder("body", "body", default = listOf("default"))
            stencil("inst", "letterhead", "name", "'Stencil Name'", listOf("body"))
            build("inst")
        }
        assertContains(render(document, emptyMap()), "default=Stencil Name")
    }

    @Test
    fun `an instance marked legacyFillScope keeps its fills in the stencil's scope`() {
        val text = render(template("params.name", mapOf(StencilNodeKeys.PROP_LEGACY_FILL_SCOPE to true)), emptyMap())
        assertContains(text, "fill=Stencil Name")
    }

    @Test
    fun `a stencil in a placeholder binds against the template's data`() {
        val document = doc {
            expr("inner-own", "inner", "params.title")
            stencil("inner", "badge", "title", "customer", listOf("inner-own"))
            placeholder("body", "body", fill = listOf("inner"))
            stencil("inst", "letterhead", "name", "'Stencil Name'", listOf("body"))
            build("inst")
        }
        assertContains(render(document, mapOf("customer" to "Jane")), "inner=Jane")
    }

    @Test
    fun `fills chain through nested stencils - each reads where it was written`() {
        // Template fills `content` of `page`; `page` puts its `content` placeholder inside the fill it
        // gives `letterhead`'s `body`, next to text that reads page's own params.
        val document = doc {
            expr("t-fill", "template", "who")
            expr("page-fill", "page", "params.a")
            placeholder("content", "content", fill = listOf("t-fill"))
            placeholder("lh-body", "body", fill = listOf("page-fill", "content"))
            expr("lh-own", "letterhead", "params.b")
            stencil("lh", "letterhead", "b", "params.a", listOf("lh-own", "lh-body"))
            stencil("inst", "page", "a", "'A'", listOf("lh"))
            build("inst")
        }

        val text = render(document, mapOf("who" to "T"))

        assertContains(text, "letterhead=A")
        assertContains(text, "page=A")
        assertContains(text, "template=T")
    }

    // ── Isolation (ADR 0006 part 5) ──────────────────────────────────────────

    @Test
    fun `a stencil does not see the template's data by default`() {
        val document = doc {
            expr("own", "own", "customer")
            stencil("inst", "letterhead", "name", "'x'", listOf("own"))
            build("inst")
        }
        val text = render(document, mapOf("customer" to "Jane"))
        assertFalse(text.contains("own=Jane"), text)
    }

    @Test
    fun `a stencil that declares readsTemplateData sees the template's data and its params`() {
        val document = doc {
            expr("own", "own", "customer")
            expr("param", "param", "params.name")
            stencil("inst", "letterhead", "name", "'Stencil Name'", listOf("own", "param"), mapOf(StencilNodeKeys.PROP_READS_TEMPLATE_DATA to true))
            build("inst")
        }
        val text = render(document, mapOf("customer" to "Jane"))
        assertContains(text, "own=Jane")
        assertContains(text, "param=Stencil Name")
    }

    @Test
    fun `an isolated stencil inside a stencil does not see the outer stencil's params`() {
        val document = doc {
            expr("inner-own", "inner", "params.a")
            node("inner", "stencil", mapOf("stencilId" to "badge"), "children" to listOf("inner-own"))
            stencil("inst", "page", "a", "'A'", listOf("inner"))
            build("inst")
        }
        assertFalse(render(document, emptyMap()).contains("inner=A"))
    }

    @Test
    fun `isolation still lets bindings read the template's data`() {
        val text = render(template("customer"), mapOf("customer" to "Jane"))
        assertContains(text, "own=Stencil Name")
        assertContains(text, "fill=Jane")
    }
}
