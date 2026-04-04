package app.epistola.suite.stencils

import app.epistola.suite.stencils.model.StencilContentReplacer
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

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
        assertThat(result.nodes).doesNotContainKey("old-text")

        // Stencil version updated
        assertThat(result.nodes["stencil-1"]?.props?.get("version")).isEqualTo(2)

        // New content added (re-keyed, so not "new-text" ID)
        val stencilSlot = result.slots["stencil-1-slot"]!!
        assertThat(stencilSlot.children).hasSize(1)
        val newChild = result.nodes[stencilSlot.children[0]]!!
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

        val s1Children = result.slots["s1-slot"]!!.children
        val s2Children = result.slots["s2-slot"]!!.children
        assertThat(s1Children).hasSize(1)
        assertThat(s2Children).hasSize(1)

        // Different IDs — independently re-keyed
        assertThat(s1Children[0]).isNotEqualTo(s2Children[0])

        // Both have the correct content
        assertThat(result.nodes[s1Children[0]]?.props?.get("content")).isEqualTo("New Content")
        assertThat(result.nodes[s2Children[0]]?.props?.get("content")).isEqualTo("New Content")
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
        assertThat(result.nodes["broken"]?.props?.get("version")).isEqualTo(1)
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
        assertThat(result.nodes["header"]?.props?.get("version")).isEqualTo(2)

        // Footer untouched
        assertThat(result.nodes["footer"]?.props?.get("version")).isEqualTo(1)
        assertThat(result.slots["f-slot"]!!.children).containsExactly("f-text")
        assertThat(result.nodes["f-text"]).isNotNull
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

        assertThat(result).isSameAs(template) // Same reference — no changes
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
        assertThat(result.nodes["text-before"]?.props?.get("content")).isEqualTo("Before")
        assertThat(result.nodes["text-after"]?.props?.get("content")).isEqualTo("After")
        assertThat(result.slots["root-slot"]!!.children).containsExactly("text-before", "stencil-1", "text-after")
    }
}
