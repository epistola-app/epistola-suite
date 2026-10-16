// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

import app.epistola.suite.stencils.PlaceholderNodeKeys
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.stencils.model.StencilInstances.Problem
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class StencilInstancesTest {

    // ── A tiny document builder ──────────────────────────────────────────────

    private class Doc {
        val nodes = linkedMapOf<String, Node>()
        val slots = linkedMapOf<String, Slot>()

        fun node(id: String, type: String, props: Map<String, Any?>? = null, vararg slotChildren: Pair<String, List<String>>): String {
            val slotIds = slotChildren.map { (name, children) ->
                "$id/$name".also { slots[it] = Slot(id = it, nodeId = id, name = name, children = children) }
            }
            nodes[id] = Node(id = id, type = type, slots = slotIds, props = props)
            return id
        }

        fun text(id: String, value: String) = node(id, "text", mapOf("content" to value))

        fun placeholder(id: String, name: String, default: List<String> = emptyList(), fill: List<String> = emptyList()) = node(
            id,
            PlaceholderNodeKeys.NODE_TYPE,
            mapOf(PlaceholderNodeKeys.PROP_NAME to name),
            PlaceholderNodeKeys.SLOT_DEFAULT to default,
            PlaceholderNodeKeys.SLOT_FILL to fill,
        )

        fun stencil(id: String, key: String, children: List<String>, props: Map<String, Any?> = emptyMap()) = node(
            id,
            StencilNodeKeys.NODE_TYPE,
            mapOf(StencilNodeKeys.PROP_STENCIL_ID to key) + props,
            StencilNodeKeys.SLOT_CHILDREN to children,
        )

        fun build(vararg rootChildren: String): TemplateDocument {
            node("root", "root", null, "children" to rootChildren.toList())
            return TemplateDocument(modelVersion = 1, root = "root", nodes = nodes, slots = slots, themeRef = ThemeRef.Inherit)
        }
    }

    private fun doc(content: Doc.() -> TemplateDocument): TemplateDocument = Doc().content()

    /** Letterhead: a logo, then a `body` placeholder with default text. */
    private val letterhead = doc {
        text("logo", "LOGO")
        text("body-default", "default body")
        placeholder("body", "body", default = listOf("body-default"))
        build("logo", "body")
    }

    private fun resolver(vararg stencils: Pair<Pair<String, String>, StencilInstances.Content>) = StencilInstances.Resolver { key, catalog ->
        stencils.toMap()[key to catalog]
    }

    private fun content(document: TemplateDocument, schema: Map<String, Any?>? = null) = StencilInstances.Content(document, schema)

    /** A template in `letters` using the letterhead, filling `body`. */
    private fun storedTemplate(props: Map<String, Any?> = emptyMap()) = doc {
        text("my-body", "Dear customer")
        placeholder("fill-body", "body", fill = listOf("my-body"))
        stencil("inst", "letterhead", listOf("fill-body"), props)
        build("inst")
    }

    private fun TemplateDocument.childrenOf(nodeId: String, slotName: String): List<String> = nodes.getValue(nodeId).slots.map(slots::getValue).single { it.name == slotName }.children

    private fun TemplateDocument.texts(): List<String> = nodes.values.filter { it.type == "text" }.map { it.props!!["content"] as String }.sorted()

    // ── Collapse ─────────────────────────────────────────────────────────────

    @Test
    fun `collapse keeps the reference, the bindings and the fills, and drops the stencil's content and pins`() {
        val expanded = doc {
            text("copy-logo", "LOGO")
            text("copy-default", "default body")
            text("my-body", "Dear customer")
            placeholder("copy-body", "body", default = listOf("copy-default"), fill = listOf("my-body"))
            placeholder("copy-empty", "footer")
            stencil(
                "inst",
                "letterhead",
                listOf("copy-logo", "copy-body", "copy-empty"),
                mapOf(
                    StencilNodeKeys.PROP_VERSION to 3,
                    StencilNodeKeys.PROP_DRAFT_VERSION to 4,
                    StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT to mapOf("type" to "object"),
                    NodeParameterKeys.PROP_PARAMETER_BINDINGS to mapOf("name" to "customer.name"),
                ),
            )
            build("inst")
        }

        val stored = StencilInstances.collapse(expanded)

        assertThat(stored.nodes.getValue("inst").props).isEqualTo(
            mapOf(
                StencilNodeKeys.PROP_STENCIL_ID to "letterhead",
                NodeParameterKeys.PROP_PARAMETER_BINDINGS to mapOf("name" to "customer.name"),
            ),
        )
        assertThat(stored.childrenOf("inst", StencilNodeKeys.SLOT_CHILDREN)).containsExactly("inst:fill:body")
        assertThat(stored.childrenOf("inst:fill:body", PlaceholderNodeKeys.SLOT_FILL)).containsExactly("my-body")
        assertThat(stored.nodes.keys).containsExactlyInAnyOrder("root", "inst", "inst:fill:body", "my-body")
        assertThat(stored.slots.keys).containsExactlyInAnyOrder("root/children", "inst:children", "inst:fill:body:fill")
        assertThat(stored.slots.values.map { it.nodeId }).allMatch { it in stored.nodes }
    }

    @Test
    fun `collapse is idempotent`() {
        val once = StencilInstances.collapse(storedTemplate())
        assertThat(StencilInstances.collapse(once)).isEqualTo(once)
    }

    @Test
    fun `a document without stencils is returned as it is`() {
        val plain = doc {
            text("t", "hello")
            build("t")
        }
        assertThat(StencilInstances.collapse(plain)).isSameAs(plain)
        assertThat(StencilInstances.expand(plain, "letters", resolver()).document).isSameAs(plain)
    }

    // ── Expand ───────────────────────────────────────────────────────────────

    @Test
    fun `expand inlines the stencil and puts the fill into its placeholder`() {
        val expanded = StencilInstances.expand(storedTemplate(), "letters", resolver(("letterhead" to "letters") to content(letterhead)))

        assertThat(expanded.findings).isEmpty()
        val document = expanded.document
        assertThat(document.childrenOf("inst", StencilNodeKeys.SLOT_CHILDREN)).containsExactly("inst:logo", "inst:body")
        assertThat(document.childrenOf("inst:body", PlaceholderNodeKeys.SLOT_FILL)).containsExactly("my-body")
        assertThat(document.childrenOf("inst:body", PlaceholderNodeKeys.SLOT_DEFAULT)).containsExactly("inst:body-default")
        assertThat(document.texts()).containsExactly("Dear customer", "LOGO", "default body")
        assertThat(document.slots.values.map { it.nodeId }).allMatch { it in document.nodes }
    }

    @Test
    fun `expanding is deterministic, and collapsing the expansion gives the stored form back`() {
        val stored = StencilInstances.collapse(storedTemplate())
        val resolver = resolver(("letterhead" to "letters") to content(letterhead))

        val first = StencilInstances.expand(stored, "letters", resolver).document
        val second = StencilInstances.expand(stored, "letters", resolver).document

        assertThat(second).isEqualTo(first)
        assertThat(StencilInstances.collapse(first)).isEqualTo(stored)
    }

    @Test
    fun `a change to the stencil reaches the template with nothing to update`() {
        val newLetterhead = doc {
            text("new-logo", "NEW LOGO")
            placeholder("body", "body")
            text("footer", "footer text")
            build("new-logo", "body", "footer")
        }

        val document = StencilInstances.expand(storedTemplate(), "letters", resolver(("letterhead" to "letters") to content(newLetterhead))).document

        assertThat(document.texts()).containsExactly("Dear customer", "NEW LOGO", "footer text")
    }

    @Test
    fun `the schema snapshot is set while expanded and dropped when stored`() {
        val schema = mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")))
        val stored = storedTemplate(mapOf(NodeParameterKeys.PROP_PARAMETER_BINDINGS to mapOf("name" to "customer.name")))

        val expanded = StencilInstances.expand(stored, "letters", resolver(("letterhead" to "letters") to content(letterhead, schema)))

        assertThat(expanded.findings).isEmpty()
        assertThat(expanded.document.nodes.getValue("inst").props!![StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT]).isEqualTo(schema)
        assertThat(StencilInstances.collapse(expanded.document).nodes.getValue("inst").props!!).doesNotContainKey(StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT)
    }

    // ── Where a template no longer fits ──────────────────────────────────────

    @Test
    fun `a fill for a placeholder the stencil no longer has is a blocking finding and is not rendered`() {
        val withoutBody = doc {
            text("logo", "LOGO")
            build("logo")
        }

        val expanded = StencilInstances.expand(storedTemplate(), "letters", resolver(("letterhead" to "letters") to content(withoutBody)))

        assertThat(expanded.findings).singleElement().satisfies({
            assertThat(it.problem).isEqualTo(Problem.UNKNOWN_PLACEHOLDER)
            assertThat(it.name).isEqualTo("body")
            assertThat(it.instanceNodeId).isEqualTo("inst")
            assertThat(it.blocking).isTrue()
        })
        assertThat(expanded.document.texts()).containsExactly("LOGO")
    }

    @Test
    fun `parameters - an unbound required one blocks, one with a default does not, an unknown binding only warns`() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string"),
                "greeting" to mapOf("type" to "string", "default" to "Dear"),
            ),
            "required" to listOf("name", "greeting"),
        )
        val stored = storedTemplate(mapOf(NodeParameterKeys.PROP_PARAMETER_BINDINGS to mapOf("gone" to "x")))

        val findings = StencilInstances.expand(stored, "letters", resolver(("letterhead" to "letters") to content(letterhead, schema))).findings

        assertThat(findings.map { it.problem to it.name }).containsExactly(
            Problem.UNBOUND_REQUIRED_PARAMETER to "name",
            Problem.UNKNOWN_PARAMETER to "gone",
        )
        assertThat(findings.map { it.blocking }).containsExactly(true, false)
    }

    @Test
    fun `a missing stencil is a finding, and the instance keeps its fills`() {
        val stored = StencilInstances.collapse(storedTemplate())

        val expanded = StencilInstances.expand(stored, "letters", resolver())

        assertThat(expanded.findings.map { it.problem }).containsExactly(Problem.STENCIL_NOT_FOUND)
        assertThat(expanded.document).isEqualTo(stored)
    }

    // ── Catalogs ─────────────────────────────────────────────────────────────

    @Test
    fun `an instance without a catalog resolves in the template's catalog, one with a catalog in that one`() {
        val shared = doc {
            text("shared-logo", "SHARED")
            build("shared-logo")
        }
        val resolver = resolver(
            ("letterhead" to "letters") to content(letterhead),
            ("letterhead" to "shared") to content(shared),
        )

        val own = StencilInstances.expand(storedTemplate(), "letters", resolver).document
        val other = StencilInstances.expand(storedTemplate(mapOf(StencilNodeKeys.PROP_CATALOG_KEY to "shared")), "letters", resolver)

        assertThat(own.texts()).contains("LOGO")
        assertThat(other.document.texts()).containsExactly("SHARED")
        // `shared`'s letterhead has no `body` placeholder.
        assertThat(other.findings.map { it.problem to it.catalogKey }).containsExactly(Problem.UNKNOWN_PLACEHOLDER to "shared")
    }

    // ── Stencils in stencils ─────────────────────────────────────────────────

    /**
     * `page` (in `shared`) wraps `letterhead` (also `shared`, referenced without a catalog). It fills
     * the letterhead's `body` with its own placeholder `content`, which is what a template fills.
     */
    private val page = doc {
        placeholder("page-content", "content")
        placeholder("lh-body", "body", fill = listOf("page-content"))
        stencil("lh", "letterhead", listOf("lh-body"))
        build("lh")
    }

    @Test
    fun `a stencil's own instances resolve in the stencil's catalog, and the template fills through them`() {
        val template = doc {
            text("my-content", "Dear customer")
            placeholder("fill-content", "content", fill = listOf("my-content"))
            stencil("inst", "page", listOf("fill-content"), mapOf(StencilNodeKeys.PROP_CATALOG_KEY to "shared"))
            build("inst")
        }
        val resolver = resolver(
            ("page" to "shared") to content(page),
            ("letterhead" to "shared") to content(letterhead),
        )

        val expanded = StencilInstances.expand(template, "letters", resolver)

        assertThat(expanded.findings).isEmpty()
        assertThat(expanded.document.texts()).containsExactly("Dear customer", "LOGO", "default body")
        // The template's fill sits in page's placeholder, inside the letterhead's body.
        assertThat(expanded.document.childrenOf("inst:lh:body", PlaceholderNodeKeys.SLOT_FILL)).containsExactly("inst:page-content")
        assertThat(expanded.document.childrenOf("inst:page-content", PlaceholderNodeKeys.SLOT_FILL)).containsExactly("my-content")
        assertThat(StencilInstances.collapse(expanded.document)).isEqualTo(StencilInstances.collapse(template))
    }

    @Test
    fun `a nested stencil that no longer fits is reported against the stencil that uses it`() {
        val letterheadWithoutBody = doc {
            text("logo", "LOGO")
            build("logo")
        }
        val template = doc {
            stencil("inst", "page", emptyList(), mapOf(StencilNodeKeys.PROP_CATALOG_KEY to "shared"))
            build("inst")
        }

        val findings = StencilInstances.expand(
            template,
            "letters",
            resolver(("page" to "shared") to content(page), ("letterhead" to "shared") to content(letterheadWithoutBody)),
        ).findings

        assertThat(findings).singleElement().satisfies({
            assertThat(it.problem).isEqualTo(Problem.UNKNOWN_PLACEHOLDER)
            assertThat(it.usedIn).isEqualTo(StencilInstances.StencilRef("page", "shared"))
            assertThat(it.stencilKey).isEqualTo("letterhead")
        })
    }

    @Test
    fun `a stencil that contains itself is a recursion, not an endless expansion`() {
        val loop = doc {
            stencil("again", "loop", emptyList())
            build("again")
        }
        val template = doc {
            stencil("inst", "loop", emptyList())
            build("inst")
        }

        val findings = StencilInstances.expand(template, "letters", resolver(("loop" to "letters") to content(loop))).findings

        assertThat(findings.map { it.problem to it.usedIn }).containsExactly(Problem.RECURSION to StencilInstances.StencilRef("loop", "letters"))
    }

    @Test
    fun `a stencil inside a template's fill resolves in the template's catalog`() {
        val template = doc {
            stencil("inner", "letterhead", emptyList())
            placeholder("fill-content", "content", fill = listOf("inner"))
            stencil("inst", "page", listOf("fill-content"), mapOf(StencilNodeKeys.PROP_CATALOG_KEY to "shared"))
            build("inst")
        }
        val ownLetterhead = doc {
            text("own", "OWN")
            build("own")
        }

        val expanded = StencilInstances.expand(
            template,
            "letters",
            resolver(
                ("page" to "shared") to content(page),
                ("letterhead" to "shared") to content(letterhead),
                ("letterhead" to "letters") to content(ownLetterhead),
            ),
        )

        assertThat(expanded.findings).isEmpty()
        assertThat(expanded.document.texts()).containsExactly("LOGO", "OWN", "default body")
    }
}
