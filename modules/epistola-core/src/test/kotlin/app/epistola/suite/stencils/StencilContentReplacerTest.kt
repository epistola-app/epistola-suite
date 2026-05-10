package app.epistola.suite.stencils

import app.epistola.suite.stencils.model.StencilContentReplacer
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

@Tag("unit")
class StencilContentReplacerTest {

    private fun doc(vararg nodes: Pair<String, Node>, slots: Map<String, Slot>, root: String = "root"): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = root,
        nodes = nodes.toMap(),
        slots = slots,
        themeRef = ThemeRef.Inherit,
    )

    private fun stencilContent(): TemplateDocument = doc(
        "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
        "new-text" to Node(id = "new-text", type = "text", slots = emptyList(), props = mapOf("content" to "New Content")),
        slots = mapOf(
            "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("new-text")),
        ),
    )

    @Test
    fun `upgrades stencil instance with new content`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-1" to Node(
                id = "stencil-1",
                type = "stencil",
                slots = listOf("stencil-1-slot"),
                props = mapOf("stencilId" to "header", "version" to 1),
            ),
            "old-text" to Node(id = "old-text", type = "text", slots = emptyList(), props = mapOf("content" to "Old")),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-1")),
                "stencil-1-slot" to Slot(id = "stencil-1-slot", nodeId = "stencil-1", name = "children", children = listOf("old-text")),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilContent())

        // Old content removed
        assertThat(result.document.nodes).doesNotContainKey("old-text")

        // Stencil version updated
        assertThat(result.document.nodes["stencil-1"]?.props?.get("version")).isEqualTo(2)

        // New content added (re-keyed, so not "new-text" ID)
        val stencilSlot = result.document.slots["stencil-1-slot"]!!
        assertThat(stencilSlot.children).hasSize(1)
        val newChild = result.document.nodes[stencilSlot.children[0]]!!
        assertThat(newChild.type).isEqualTo("text")
        assertThat(newChild.props?.get("content")).isEqualTo("New Content")
        assertThat(newChild.id).isNotEqualTo("new-text") // re-keyed
    }

    @Test
    fun `multiple instances get independent re-keyed IDs`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "s1" to Node(id = "s1", type = "stencil", slots = listOf("s1-slot"), props = mapOf("stencilId" to "header", "version" to 1)),
            "s2" to Node(id = "s2", type = "stencil", slots = listOf("s2-slot"), props = mapOf("stencilId" to "header", "version" to 1)),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("s1", "s2")),
                "s1-slot" to Slot(id = "s1-slot", nodeId = "s1", name = "children", children = emptyList()),
                "s2-slot" to Slot(id = "s2-slot", nodeId = "s2", name = "children", children = emptyList()),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilContent())

        val s1Children = result.document.slots["s1-slot"]!!.children
        val s2Children = result.document.slots["s2-slot"]!!.children
        assertThat(s1Children).hasSize(1)
        assertThat(s2Children).hasSize(1)

        // Different IDs — independently re-keyed
        assertThat(s1Children[0]).isNotEqualTo(s2Children[0])

        // Both have the correct content
        assertThat(result.document.nodes[s1Children[0]]?.props?.get("content")).isEqualTo("New Content")
        assertThat(result.document.nodes[s2Children[0]]?.props?.get("content")).isEqualTo("New Content")
    }

    @Test
    fun `stencil with no slots is skipped`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "broken" to Node(id = "broken", type = "stencil", slots = emptyList(), props = mapOf("stencilId" to "header", "version" to 1)),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("broken")),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilContent())

        // Node still exists, version NOT updated (skipped)
        assertThat(result.document.nodes["broken"]?.props?.get("version")).isEqualTo(1)
    }

    @Test
    fun `non-matching stencilId nodes are untouched`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "header" to Node(id = "header", type = "stencil", slots = listOf("h-slot"), props = mapOf("stencilId" to "header", "version" to 1)),
            "footer" to Node(id = "footer", type = "stencil", slots = listOf("f-slot"), props = mapOf("stencilId" to "footer", "version" to 1)),
            "h-text" to Node(id = "h-text", type = "text", slots = emptyList()),
            "f-text" to Node(id = "f-text", type = "text", slots = emptyList()),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("header", "footer")),
                "h-slot" to Slot(id = "h-slot", nodeId = "header", name = "children", children = listOf("h-text")),
                "f-slot" to Slot(id = "f-slot", nodeId = "footer", name = "children", children = listOf("f-text")),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilContent())

        // Header upgraded
        assertThat(result.document.nodes["header"]?.props?.get("version")).isEqualTo(2)

        // Footer untouched
        assertThat(result.document.nodes["footer"]?.props?.get("version")).isEqualTo(1)
        assertThat(result.document.slots["f-slot"]!!.children).containsExactly("f-text")
        assertThat(result.document.nodes["f-text"]).isNotNull
    }

    @Test
    fun `returns original document when no matching stencils`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "text" to Node(id = "text", type = "text", slots = emptyList()),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("text")),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilContent())

        assertThat(result.document).isSameAs(template) // Same reference — no changes
        assertThat(result.droppedFills).isEmpty()
    }

    @Test
    fun `preserves non-stencil nodes in the document`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "text-before" to Node(id = "text-before", type = "text", slots = emptyList(), props = mapOf("content" to "Before")),
            "stencil-1" to Node(id = "stencil-1", type = "stencil", slots = listOf("s-slot"), props = mapOf("stencilId" to "header", "version" to 1)),
            "text-after" to Node(id = "text-after", type = "text", slots = emptyList(), props = mapOf("content" to "After")),
            "old-child" to Node(id = "old-child", type = "text", slots = emptyList()),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("text-before", "stencil-1", "text-after")),
                "s-slot" to Slot(id = "s-slot", nodeId = "stencil-1", name = "children", children = listOf("old-child")),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilContent())

        // Surrounding nodes preserved
        assertThat(result.document.nodes["text-before"]?.props?.get("content")).isEqualTo("Before")
        assertThat(result.document.nodes["text-after"]?.props?.get("content")).isEqualTo("After")
        assertThat(result.document.slots["root-slot"]!!.children).containsExactly("text-before", "stencil-1", "text-after")
    }

    // ---------------------------------------------------------------------------
    // Fill preservation across upgrades
    // ---------------------------------------------------------------------------

    /** New stencil version content: a placeholder named "body" with a default text. */
    private fun stencilV2WithPlaceholder(): TemplateDocument = doc(
        "v2-root" to Node(id = "v2-root", type = "root", slots = listOf("v2-root-slot")),
        "v2-ph" to Node(
            id = "v2-ph",
            type = "placeholder",
            slots = listOf("v2-ph-fill"),
            props = mapOf("name" to "body", "kind" to "block"),
        ),
        "v2-default-text" to Node(
            id = "v2-default-text",
            type = "text",
            slots = emptyList(),
            props = mapOf("content" to "v2 default text"),
        ),
        slots = mapOf(
            "v2-root-slot" to Slot(id = "v2-root-slot", nodeId = "v2-root", name = "children", children = listOf("v2-ph")),
            "v2-ph-fill" to Slot(id = "v2-ph-fill", nodeId = "v2-ph", name = "fill", children = listOf("v2-default-text")),
        ),
        root = "v2-root",
    )

    /** New stencil version content: placeholder renamed from "body" to "main". */
    private fun stencilV3RenamePlaceholder(): TemplateDocument = doc(
        "v3-root" to Node(id = "v3-root", type = "root", slots = listOf("v3-root-slot")),
        "v3-ph" to Node(
            id = "v3-ph",
            type = "placeholder",
            slots = listOf("v3-ph-fill"),
            props = mapOf("name" to "main", "kind" to "block"),
        ),
        slots = mapOf(
            "v3-root-slot" to Slot(id = "v3-root-slot", nodeId = "v3-root", name = "children", children = listOf("v3-ph")),
            "v3-ph-fill" to Slot(id = "v3-ph-fill", nodeId = "v3-ph", name = "fill", children = emptyList()),
        ),
        root = "v3-root",
    )

    /** Template with one stencil instance that has filled placeholder "body". */
    private fun templateWithFilledPlaceholder(): TemplateDocument = doc(
        "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
        "stencil-1" to Node(
            id = "stencil-1",
            type = "stencil",
            slots = listOf("stencil-1-slot"),
            props = mapOf("stencilId" to "header", "version" to 1),
        ),
        "old-ph" to Node(
            id = "old-ph",
            type = "placeholder",
            slots = listOf("old-ph-fill"),
            props = mapOf("name" to "body", "kind" to "block"),
        ),
        "user-fill" to Node(
            id = "user-fill",
            type = "text",
            slots = emptyList(),
            props = mapOf("content" to "User-authored fill"),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-1")),
            "stencil-1-slot" to Slot(id = "stencil-1-slot", nodeId = "stencil-1", name = "children", children = listOf("old-ph")),
            "old-ph-fill" to Slot(id = "old-ph-fill", nodeId = "old-ph", name = "fill", children = listOf("user-fill")),
        ),
    )

    @Test
    fun `preserves user fill across upgrade when placeholder name matches`() {
        val template = templateWithFilledPlaceholder()
        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilV2WithPlaceholder())

        // Find the new placeholder ("body") in the upgraded doc
        val newPlaceholders = result.document.nodes.values.filter { it.type == "placeholder" }
        assertThat(newPlaceholders).hasSize(1)
        val newPh = newPlaceholders[0]
        assertThat(newPh.props?.get("name")).isEqualTo("body")

        val newPhFillId = newPh.slots[0]
        val newPhFill = result.document.slots[newPhFillId]!!
        assertThat(newPhFill.children).hasSize(1)

        // The filled child carries the user's content (re-keyed, so different ID)
        val fillChild = result.document.nodes[newPhFill.children[0]]!!
        assertThat(fillChild.type).isEqualTo("text")
        assertThat(fillChild.props?.get("content")).isEqualTo("User-authored fill")
        assertThat(fillChild.id).isNotEqualTo("user-fill")

        // The v2 default text was discarded
        assertThat(result.document.nodes.values.none { it.props?.get("content") == "v2 default text" }).isTrue()
        assertThat(result.droppedFills).isEmpty()
    }

    @Test
    fun `dropped fills are reported when placeholder is renamed`() {
        val template = templateWithFilledPlaceholder()
        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 3, stencilV3RenamePlaceholder())

        // The new "main" placeholder is empty (no captured fill matched)
        val newPh = result.document.nodes.values.first { it.type == "placeholder" }
        assertThat(newPh.props?.get("name")).isEqualTo("main")
        val newPhFill = result.document.slots[newPh.slots[0]]!!
        assertThat(newPhFill.children).isEmpty()

        // The "body" fill is reported as dropped under the stencil instance node id
        assertThat(result.droppedFills).containsKey("stencil-1")
        val dropped = result.droppedFills["stencil-1"]!!
        assertThat(dropped).hasSize(1)
        assertThat(dropped[0].name).isEqualTo("body")
        assertThat(dropped[0].contentSummary).contains("User-authored fill")

        // The user's old text is gone from the doc
        assertThat(result.document.nodes.values.none { it.props?.get("content") == "User-authored fill" }).isTrue()
    }

    @Test
    fun `two-slot placeholder - preserves user fill and keeps new default untouched`() {
        // Old (v1) stencil placeholder has both default ("v1 default") and a user
        // override in the fill slot ("user override"). New (v2) stencil has a
        // changed default ("v2 default") and an empty fill (as all stencil
        // definitions ship). After upgrade:
        //   - the new placeholder's `default` slot keeps "v2 default"
        //   - the new placeholder's `fill` slot has "user override" (preserved)
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-1" to Node(
                id = "stencil-1",
                type = "stencil",
                slots = listOf("stencil-1-slot"),
                props = mapOf("stencilId" to "header", "version" to 1),
            ),
            "ph" to Node(
                id = "ph",
                type = "placeholder",
                slots = listOf("ph-default", "ph-fill"),
                props = mapOf("name" to "body"),
            ),
            "v1-default-text" to Node(
                id = "v1-default-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "v1 default"),
            ),
            "user-override" to Node(
                id = "user-override",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "user override"),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-1")),
                "stencil-1-slot" to Slot("stencil-1-slot", "stencil-1", "children", listOf("ph")),
                "ph-default" to Slot("ph-default", "ph", "default", listOf("v1-default-text")),
                "ph-fill" to Slot("ph-fill", "ph", "fill", listOf("user-override")),
            ),
        )

        val v2Content = doc(
            "v2-root" to Node(id = "v2-root", type = "root", slots = listOf("v2-root-slot")),
            "v2-ph" to Node(
                id = "v2-ph",
                type = "placeholder",
                slots = listOf("v2-ph-default", "v2-ph-fill"),
                props = mapOf("name" to "body"),
            ),
            "v2-default-text" to Node(
                id = "v2-default-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "v2 default"),
            ),
            slots = mapOf(
                "v2-root-slot" to Slot("v2-root-slot", "v2-root", "children", listOf("v2-ph")),
                "v2-ph-default" to Slot("v2-ph-default", "v2-ph", "default", listOf("v2-default-text")),
                "v2-ph-fill" to Slot("v2-ph-fill", "v2-ph", "fill", emptyList()),
            ),
            root = "v2-root",
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, v2Content)

        // Find the upgraded placeholder.
        val newPh = result.document.nodes.values.first { it.type == "placeholder" }
        val newDefaultSlot = result.document.slots[newPh.slots.first { result.document.slots[it]?.name == "default" }]!!
        val newFillSlot = result.document.slots[newPh.slots.first { result.document.slots[it]?.name == "fill" }]!!

        // The new default has v2 default (not v1, not the user override).
        assertThat(newDefaultSlot.children).hasSize(1)
        assertThat(result.document.nodes[newDefaultSlot.children[0]]?.props?.get("content")).isEqualTo("v2 default")

        // The fill carries the user override.
        assertThat(newFillSlot.children).hasSize(1)
        assertThat(result.document.nodes[newFillSlot.children[0]]?.props?.get("content")).isEqualTo("user override")

        // The old default text was discarded (v1 default replaced by v2).
        assertThat(result.document.nodes.values.none { it.props?.get("content") == "v1 default" }).isTrue()
        assertThat(result.droppedFills).isEmpty()
    }

    @Test
    fun `two-slot placeholder - empty fill is not captured`() {
        // The placeholder has a populated default but an empty fill — meaning
        // the user has not overridden. On upgrade, the new default should win;
        // nothing should be reported as dropped.
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-1" to Node(
                id = "stencil-1",
                type = "stencil",
                slots = listOf("stencil-1-slot"),
                props = mapOf("stencilId" to "header", "version" to 1),
            ),
            "ph" to Node(
                id = "ph",
                type = "placeholder",
                slots = listOf("ph-default", "ph-fill"),
                props = mapOf("name" to "body"),
            ),
            "v1-default" to Node(
                id = "v1-default",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "v1 default"),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-1")),
                "stencil-1-slot" to Slot("stencil-1-slot", "stencil-1", "children", listOf("ph")),
                "ph-default" to Slot("ph-default", "ph", "default", listOf("v1-default")),
                "ph-fill" to Slot("ph-fill", "ph", "fill", emptyList()),
            ),
        )

        val v2 = doc(
            "v2-root" to Node(id = "v2-root", type = "root", slots = listOf("v2-root-slot")),
            "v2-ph" to Node(
                id = "v2-ph",
                type = "placeholder",
                slots = listOf("v2-ph-default", "v2-ph-fill"),
                props = mapOf("name" to "body"),
            ),
            "v2-default" to Node(
                id = "v2-default",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "v2 default"),
            ),
            slots = mapOf(
                "v2-root-slot" to Slot("v2-root-slot", "v2-root", "children", listOf("v2-ph")),
                "v2-ph-default" to Slot("v2-ph-default", "v2-ph", "default", listOf("v2-default")),
                "v2-ph-fill" to Slot("v2-ph-fill", "v2-ph", "fill", emptyList()),
            ),
            root = "v2-root",
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, v2)

        val newPh = result.document.nodes.values.first { it.type == "placeholder" }
        val newDefault = result.document.slots[newPh.slots.first { result.document.slots[it]?.name == "default" }]!!
        val newFill = result.document.slots[newPh.slots.first { result.document.slots[it]?.name == "fill" }]!!

        assertThat(result.document.nodes[newDefault.children[0]]?.props?.get("content")).isEqualTo("v2 default")
        assertThat(newFill.children).isEmpty()
        assertThat(result.droppedFills).isEmpty()
    }

    @Test
    fun `preserves fills independently across multiple stencil instances`() {
        val template = doc(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "s1" to Node(id = "s1", type = "stencil", slots = listOf("s1-slot"), props = mapOf("stencilId" to "header", "version" to 1)),
            "s2" to Node(id = "s2", type = "stencil", slots = listOf("s2-slot"), props = mapOf("stencilId" to "header", "version" to 1)),
            "ph1" to Node(id = "ph1", type = "placeholder", slots = listOf("ph1-fill"), props = mapOf("name" to "body")),
            "ph2" to Node(id = "ph2", type = "placeholder", slots = listOf("ph2-fill"), props = mapOf("name" to "body")),
            "fill1" to Node(id = "fill1", type = "text", slots = emptyList(), props = mapOf("content" to "Fill A")),
            "fill2" to Node(id = "fill2", type = "text", slots = emptyList(), props = mapOf("content" to "Fill B")),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("s1", "s2")),
                "s1-slot" to Slot(id = "s1-slot", nodeId = "s1", name = "children", children = listOf("ph1")),
                "ph1-fill" to Slot(id = "ph1-fill", nodeId = "ph1", name = "fill", children = listOf("fill1")),
                "s2-slot" to Slot(id = "s2-slot", nodeId = "s2", name = "children", children = listOf("ph2")),
                "ph2-fill" to Slot(id = "ph2-fill", nodeId = "ph2", name = "fill", children = listOf("fill2")),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(template, "header", 2, stencilV2WithPlaceholder())

        // s1's slot now has a re-keyed placeholder; its fill should carry "Fill A"
        val s1Slot = result.document.slots["s1-slot"]!!
        val s1NewPh = result.document.nodes[s1Slot.children[0]]!!
        val s1Fill = result.document.slots[s1NewPh.slots[0]]!!
        assertThat(result.document.nodes[s1Fill.children[0]]?.props?.get("content")).isEqualTo("Fill A")

        // s2's slot's placeholder fill carries "Fill B" — independent
        val s2Slot = result.document.slots["s2-slot"]!!
        val s2NewPh = result.document.nodes[s2Slot.children[0]]!!
        val s2Fill = result.document.slots[s2NewPh.slots[0]]!!
        assertThat(result.document.nodes[s2Fill.children[0]]?.props?.get("content")).isEqualTo("Fill B")

        // The two fills must have distinct IDs (re-keyed independently)
        assertThat(s1Fill.children[0]).isNotEqualTo(s2Fill.children[0])
    }

    // ── Parameter binding preservation across upgrades ──────────────────────

    private val mapper = ObjectMapper()

    private fun schema(json: String): ObjectNode = mapper.readValue(json, ObjectNode::class.java)

    private fun stencilWithParameters(
        bindings: Map<String, String>,
        oldSnapshot: ObjectNode? = null,
    ): Node {
        val props = mutableMapOf<String, Any?>(
            "stencilId" to "header",
            "version" to 1,
            "parameterBindings" to bindings,
        )
        if (oldSnapshot != null) props["parameterSchemaSnapshot"] = oldSnapshot
        return Node(id = "stencil-1", type = "stencil", slots = listOf("stencil-1-slot"), props = props)
    }

    private fun parametrisedTemplate(stencilNode: Node): TemplateDocument = doc(
        "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
        "stencil-1" to stencilNode,
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-1")),
            "stencil-1-slot" to Slot(id = "stencil-1-slot", nodeId = "stencil-1", name = "children", children = emptyList()),
        ),
    )

    @Test
    fun `preserves bindings whose param names still exist in the new schema`() {
        val newSchema = schema(
            """{"type":"object","properties":{"name":{"type":"string"}}}""",
        )
        val template = parametrisedTemplate(
            stencilWithParameters(bindings = mapOf("name" to "customer.name")),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(
            template,
            stencilId = "header",
            newVersion = 2,
            newContent = stencilContent(),
            newParameterSchema = newSchema,
        )

        @Suppress("UNCHECKED_CAST")
        val bindings = result.document.nodes["stencil-1"]?.props?.get("parameterBindings") as? Map<String, String>
        assertThat(bindings).containsEntry("name", "customer.name")
        assertThat(result.droppedBindings).isEmpty()
        assertThat(result.unboundRequired).isEmpty()
    }

    @Test
    fun `reports droppedBindings when a bound param is removed from the schema`() {
        val newSchema = schema(
            """{"type":"object","properties":{"name":{"type":"string"}}}""",
        )
        val template = parametrisedTemplate(
            stencilWithParameters(
                bindings = mapOf(
                    "name" to "customer.name",
                    "removed" to "old.expr",
                ),
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(
            template,
            stencilId = "header",
            newVersion = 2,
            newContent = stencilContent(),
            newParameterSchema = newSchema,
        )

        // Surviving binding stays.
        @Suppress("UNCHECKED_CAST")
        val bindings = result.document.nodes["stencil-1"]?.props?.get("parameterBindings") as? Map<String, String>
        assertThat(bindings).containsEntry("name", "customer.name")
        assertThat(bindings).doesNotContainKey("removed")

        // Dropped one is reported with its expression so the UI can show it.
        assertThat(result.droppedBindings).containsKey("stencil-1")
        val dropped = result.droppedBindings["stencil-1"]!!
        assertThat(dropped).hasSize(1)
        assertThat(dropped[0].name).isEqualTo("removed")
        assertThat(dropped[0].expression).isEqualTo("old.expr")
    }

    @Test
    fun `reports unboundRequired when a newly-required param has no preserved binding`() {
        val newSchema = schema(
            """
            {"type":"object","properties":{"name":{"type":"string"},"newReq":{"type":"string"}},
             "required":["newReq"]}
            """.trimIndent(),
        )
        val template = parametrisedTemplate(
            stencilWithParameters(bindings = mapOf("name" to "customer.name")),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(
            template,
            stencilId = "header",
            newVersion = 2,
            newContent = stencilContent(),
            newParameterSchema = newSchema,
        )

        assertThat(result.unboundRequired).containsKey("stencil-1")
        assertThat(result.unboundRequired["stencil-1"]).containsExactly("newReq")
    }

    @Test
    fun `unboundRequired skips required params that have a default value`() {
        val newSchema = schema(
            """
            {"type":"object","properties":{
              "name":{"type":"string","default":"Anonymous"}
            },"required":["name"]}
            """.trimIndent(),
        )
        val template = parametrisedTemplate(stencilWithParameters(bindings = emptyMap()))

        val result = StencilContentReplacer.upgradeStencilInstances(
            template,
            stencilId = "header",
            newVersion = 2,
            newContent = stencilContent(),
            newParameterSchema = newSchema,
        )

        assertThat(result.unboundRequired).isEmpty()
    }

    @Test
    fun `refreshes parameterSchemaSnapshot prop with the new version's schema`() {
        val oldSchema = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
        val newSchema = schema(
            """
            {"type":"object","properties":{"name":{"type":"string"},"extra":{"type":"integer"}}}
            """.trimIndent(),
        )
        val template = parametrisedTemplate(
            stencilWithParameters(
                bindings = mapOf("name" to "customer.name"),
                oldSnapshot = oldSchema,
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(
            template,
            stencilId = "header",
            newVersion = 2,
            newContent = stencilContent(),
            newParameterSchema = newSchema,
        )

        val snapshot = result.document.nodes["stencil-1"]?.props?.get("parameterSchemaSnapshot")
        assertThat(snapshot).isEqualTo(newSchema)
    }

    @Test
    fun `drops the snapshot prop when the new version has no parameter schema`() {
        val oldSchema = schema("""{"type":"object","properties":{"name":{"type":"string"}}}""")
        val template = parametrisedTemplate(
            stencilWithParameters(
                bindings = mapOf("name" to "customer.name"),
                oldSnapshot = oldSchema,
            ),
        )

        val result = StencilContentReplacer.upgradeStencilInstances(
            template,
            stencilId = "header",
            newVersion = 2,
            newContent = stencilContent(),
            newParameterSchema = null,
        )

        assertThat(result.document.nodes["stencil-1"]?.props).doesNotContainKey("parameterSchemaSnapshot")
        // All bindings dropped because nothing in the new schema declares them.
        assertThat(result.document.nodes["stencil-1"]?.props).doesNotContainKey("parameterBindings")
    }
}
