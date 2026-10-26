// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.generation.ProseMirrorConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.properties.Property
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeRendererRegistryTest {

    private val registry = NodeRendererRegistry(
        mapOf(
            "root" to ContainerNodeRenderer(),
            "text" to TextNodeRenderer(),
            "image" to ImageNodeRenderer(),
            "pagebreak" to PageBreakNodeRenderer(),
            // Emits three blocks, as a loop over three items does.
            "multi" to object : NodeRenderer {
                override fun render(
                    node: Node,
                    document: TemplateDocument,
                    context: RenderContext,
                    registry: NodeRendererRegistry,
                ): List<IElement> = List(3) { Div() }
            },
            "empty" to object : NodeRenderer {
                override fun render(
                    node: Node,
                    document: TemplateDocument,
                    context: RenderContext,
                    registry: NodeRendererRegistry,
                ): List<IElement> = emptyList()
            },
        ),
    )
    private val evaluator = CompositeExpressionEvaluator()
    private val fontCache = FontCache(pdfaCompliant = false)
    private val proseMirrorConverter = ProseMirrorConverter(evaluator)

    private fun contextFor(
        doc: TemplateDocument,
        presets: Map<String, Map<String, Any>> = emptyMap(),
        renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
        assetResolver: AssetResolver? = null,
    ) = RenderContext(
        data = emptyMap(),
        expressionEvaluator = evaluator,
        proseMirrorConverter = proseMirrorConverter,
        fontCache = fontCache,
        document = doc,
        blockStylePresets = presets,
        renderingDefaults = renderingDefaults,
        assetResolver = assetResolver,
    )

    private fun siblingDocument(vararg children: Node): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "n-root",
        nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-main"))) +
            children.associateBy(Node::id),
        slots = mapOf(
            "s-main" to Slot(
                id = "s-main",
                nodeId = "n-root",
                name = "children",
                children = children.map(Node::id),
            ),
        ),
        themeRef = ThemeRef.Inherit,
    )

    @Test
    fun `renderNode throws on unknown node type`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf(
                "n-root" to Node(id = "n-root", type = "root", slots = listOf("s-main")),
                "n-bad" to Node(id = "n-bad", type = "nonexistent-type", slots = emptyList()),
            ),
            slots = mapOf(
                "s-main" to Slot(id = "s-main", nodeId = "n-root", name = "children", children = listOf("n-bad")),
            ),
            themeRef = ThemeRef.Inherit,
        )

        val error = assertFailsWith<IllegalStateException> {
            registry.renderNode("n-bad", doc, contextFor(doc))
        }
        assertContains(error.message!!, "Unknown node type 'nonexistent-type'")
        assertContains(error.message!!, "n-bad")
    }

    @Test
    fun `renderNode throws on missing node ID`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf(
                "n-root" to Node(id = "n-root", type = "root", slots = emptyList()),
            ),
            slots = emptyMap(),
            themeRef = ThemeRef.Inherit,
        )

        val error = assertFailsWith<IllegalStateException> {
            registry.renderNode("n-does-not-exist", doc, contextFor(doc))
        }
        assertContains(error.message!!, "n-does-not-exist")
        assertContains(error.message!!, "not found")
    }

    @Test
    fun `keepWithNext groups a node with its following sibling`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "b", type = "text", slots = emptyList()),
            Node(id = "c", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        assertEquals(2, elements.size)
        val group = assertIs<Div>(elements.first())
        assertTrue(group.getProperty<Boolean>(Property.KEEP_TOGETHER) == true)
        assertEquals(2, group.children.size)
    }

    @Test
    fun `keepWithNext chain remains grouped when its final node also requests a follower`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "b", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        val group = assertIs<Div>(elements.single())
        assertEquals(2, group.children.size)
    }

    @Test
    fun `keepWithNext keeps an image with the block after it`() {
        val png = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val doc = siblingDocument(
            Node(id = "logo", type = "image", slots = emptyList(), props = mapOf("imageKey" to "logo"), styles = mapOf("keepWithNext" to true)),
            Node(id = "caption", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc, assetResolver = AssetResolver { _, _ -> AssetResolution(png, "image/png") }))

        val group = assertIs<Div>(elements.single())
        assertTrue(isGroup(group))
        assertEquals(2, group.children.size)
    }

    private fun isGroup(element: IElement) = element is Div && element.getProperty<Boolean>(Property.KEEP_TOGETHER) == true

    @Test
    fun `from V6 keepWithNext binds to the first block of a follower that emits several`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "loop", type = "multi", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        assertEquals(3, elements.size, "the group, then the follower's other two blocks")
        assertEquals(2, (elements.first() as Div).children.size)
        assertTrue(isGroup(elements.first()))
        elements.drop(1).forEach { assertTrue(!isGroup(it)) }
    }

    @Test
    fun `before V6 keepWithNext binds to everything the follower emits`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "loop", type = "multi", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc, renderingDefaults = RenderingDefaults.V5))

        val group = assertIs<Div>(elements.single())
        assertEquals(4, group.children.size)
    }

    @Test
    fun `a multi-block follower that keeps with next binds its last block to the next sibling`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "loop", type = "multi", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "b", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        // [a + first block], the middle block, [last block + b]
        assertEquals(3, elements.size)
        assertTrue(isGroup(elements[0]))
        assertEquals(2, (elements[0] as Div).children.size)
        assertTrue(!isGroup(elements[1]))
        assertTrue(isGroup(elements[2]))
        assertEquals(2, (elements[2] as Div).children.size)
    }

    @Test
    fun `inline false overrides preset keepWithNext`() {
        val doc = siblingDocument(
            Node(
                id = "a",
                type = "text",
                slots = emptyList(),
                stylePreset = "heading",
                styles = mapOf("keepWithNext" to false),
            ),
            Node(id = "b", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot(
            "s-main",
            doc,
            contextFor(doc, presets = mapOf("heading" to mapOf("keepWithNext" to true))),
        )

        assertEquals(2, elements.size)
        elements.forEach { assertNull(it.getProperty<Boolean>(Property.KEEP_TOGETHER)) }
    }

    @Test
    fun `preset keepWithNext groups siblings`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), stylePreset = "heading"),
            Node(id = "b", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot(
            "s-main",
            doc,
            contextFor(doc, presets = mapOf("heading" to mapOf("keepWithNext" to true))),
        )

        assertIs<Div>(elements.single())
    }

    @Test
    fun `final keepWithNext node renders without a wrapper`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        assertEquals(1, elements.size)
        assertNull(elements.single().getProperty<Boolean>(Property.KEEP_TOGETHER))
    }

    @Test
    fun `non-rendering siblings are transparent to keepWithNext`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "hidden", type = "empty", slots = emptyList()),
            Node(id = "b", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        val group = assertIs<Div>(elements.single())
        assertEquals(2, group.children.size)
    }

    @Test
    fun `page breaks terminate keepWithNext groups`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "break", type = "pagebreak", slots = emptyList()),
            Node(id = "b", type = "text", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        assertEquals(3, elements.size)
        assertNull(elements.first().getProperty<Boolean>(Property.KEEP_TOGETHER))
        assertIs<AreaBreak>(elements[1])
    }

    @Test
    fun `page break preserves completed links within a keepWithNext chain`() {
        val doc = siblingDocument(
            Node(id = "a", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "b", type = "text", slots = emptyList(), styles = mapOf("keepWithNext" to true)),
            Node(id = "break", type = "pagebreak", slots = emptyList()),
        )

        val elements = registry.renderSlot("s-main", doc, contextFor(doc))

        assertEquals(2, elements.size)
        assertIs<Div>(elements.first())
        assertIs<AreaBreak>(elements[1])
    }
}
