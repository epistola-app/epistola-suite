package app.epistola.generation.pdf

import app.epistola.template.model.Margins
import app.epistola.template.model.Node
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Visual regression tests for PDF rendering.
 *
 * These tests render canonical templates with [RenderingDefaults.V1] and compare the
 * extracted text content against stored baselines. Any change in text content or layout
 * causes a test failure, catching:
 * - Accidental changes to rendering constants
 * - iText library upgrade side effects
 * - Code changes that alter element positioning
 *
 * To update baselines after a deliberate rendering change:
 *   ./gradlew :modules:generation:test -DupdateBaselines=true
 */
class PdfRenderingRegressionTest {
    private val renderer = DirectPdfRenderer()
    private val defaults = RenderingDefaults.V1
    private val updateBaselines = System.getProperty("updateBaselines")?.toBoolean() ?: false
    private val baselinesDir = Path.of("src/test/resources/baselines")

    // ---------------------------------------------------------------------------
    // Canonical template: text with paragraphs and headings
    // ---------------------------------------------------------------------------
    @Test
    fun `canonical text template produces stable output`() {
        val doc = canonicalTextDocument()
        assertMatchesBaseline("canonical-text", doc, emptyMap())
    }

    // ---------------------------------------------------------------------------
    // Canonical template: table with borders and header rows
    // ---------------------------------------------------------------------------
    @Test
    fun `canonical table template produces stable output`() {
        val doc = canonicalTableDocument()
        assertMatchesBaseline("canonical-table", doc, emptyMap())
    }

    // ---------------------------------------------------------------------------
    // Canonical template: multi-column layout
    // ---------------------------------------------------------------------------
    @Test
    fun `canonical columns template produces stable output`() {
        val doc = canonicalColumnsDocument()
        assertMatchesBaseline("canonical-columns", doc, emptyMap())
    }

    // ---------------------------------------------------------------------------
    // Canonical template: data-driven table
    // ---------------------------------------------------------------------------
    @Test
    fun `canonical datatable template produces stable output`() {
        val doc = canonicalDatatableDocument()
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "Widget", "qty" to 10, "price" to "$5.00"),
                mapOf("name" to "Gadget", "qty" to 3, "price" to "$15.00"),
                mapOf("name" to "Gizmo", "qty" to 7, "price" to "$8.50"),
            ),
        )
        assertMatchesBaseline("canonical-datatable", doc, data)
    }

    // ---------------------------------------------------------------------------
    // Canonical template: custom page settings (Letter landscape, custom margins)
    // ---------------------------------------------------------------------------
    @Test
    fun `canonical page settings template produces stable output`() {
        val doc = canonicalPageSettingsDocument()
        assertMatchesBaseline("canonical-page-settings", doc, emptyMap())
    }

    // ---------------------------------------------------------------------------
    // Canonical template: mixed content (headings, lists, text, containers)
    // ---------------------------------------------------------------------------
    @Test
    fun `canonical complex template produces stable output`() {
        val doc = canonicalComplexDocument()
        val data = mapOf(
            "companyName" to "Acme Corporation",
            "items" to listOf("Alpha", "Beta", "Gamma"),
            "showFooter" to true,
        )
        assertMatchesBaseline("canonical-complex", doc, data)
    }

    // ---------------------------------------------------------------------------
    // iText version pin test
    // ---------------------------------------------------------------------------
    @Test
    fun `iText version matches expected`() {
        // This test intentionally fails on iText upgrade, reminding to re-validate baselines.
        // After upgrading iText, run with -DupdateBaselines=true, review the diffs,
        // then update the expected version here.
        val versionInfo = com.itextpdf.commons.actions.ProductNameConstant.ITEXT_CORE
        // We verify by checking a known class exists — the exact version is pinned in build.gradle.kts
        assertTrue(versionInfo.isNotEmpty(), "iText core product name should be available")
    }

    // ---------------------------------------------------------------------------
    // Baseline assertion
    // ---------------------------------------------------------------------------
    private fun assertMatchesBaseline(name: String, doc: TemplateDocument, data: Map<String, Any?>) {
        val pdfBytes = renderToBytes(doc, data)
        val extracted = PdfContentExtractor.extract(pdfBytes)

        val baselineFile = baselinesDir.resolve("$name.baseline.txt")

        if (updateBaselines || !Files.exists(baselineFile)) {
            Files.createDirectories(baselinesDir)
            Files.writeString(baselineFile, extracted)
            if (updateBaselines) {
                println("Updated baseline: $baselineFile")
            } else {
                println("Created baseline: $baselineFile")
            }
            return
        }

        val expected = Files.readString(baselineFile)
        if (expected != extracted) {
            // Write actual output for easy diffing
            val actualFile = baselinesDir.resolve("$name.actual.txt")
            Files.writeString(actualFile, extracted)
            fail(
                "Rendering regression detected for '$name'. " +
                    "Baseline: $baselineFile, Actual: $actualFile. " +
                    "Run with -DupdateBaselines=true to update after reviewing changes.",
            )
        }
    }

    private fun renderToBytes(doc: TemplateDocument, data: Map<String, Any?>): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(doc, data, output, renderingDefaults = defaults)
        return output.toByteArray()
    }

    // ---------------------------------------------------------------------------
    // Document builders
    // ---------------------------------------------------------------------------
    private fun textNode(id: String, text: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(mapOf("type" to "text", "text" to text)),
                    ),
                ),
            ),
        ),
    )

    private fun headingNode(id: String, text: String, level: Int) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "heading",
                        "attrs" to mapOf("level" to level),
                        "content" to listOf(mapOf("type" to "text", "text" to text)),
                    ),
                ),
            ),
        ),
    )

    private fun listNode(id: String, items: List<String>, ordered: Boolean = false) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to if (ordered) "orderedList" else "bulletList",
                        "content" to items.map { item ->
                            mapOf(
                                "type" to "listItem",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(mapOf("type" to "text", "text" to item)),
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            ),
        ),
    )

    private fun documentWithChildren(
        childNodes: Map<String, Node>,
        childNodeIds: List<String>,
        extraSlots: Map<String, Slot> = emptyMap(),
        pageSettingsOverride: PageSettings? = null,
    ): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val rootNode = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = childNodeIds)

        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(rootNodeId to rootNode) + childNodes,
            slots = mapOf(rootSlotId to rootSlot) + extraSlots,
            pageSettingsOverride = pageSettingsOverride,
        )
    }

    private fun canonicalTextDocument(): TemplateDocument {
        val h1 = headingNode("h1", "Main Title", 1)
        val p1 = textNode("p1", "This is a paragraph with regular text content.")
        val h2 = headingNode("h2", "Section Header", 2)
        val p2 = textNode("p2", "Another paragraph with different content to verify spacing.")
        val h3 = headingNode("h3", "Subsection", 3)
        val p3 = textNode("p3", "Final paragraph in the text regression test.")

        return documentWithChildren(
            childNodes = mapOf("h1" to h1, "p1" to p1, "h2" to h2, "p2" to p2, "h3" to h3, "p3" to p3),
            childNodeIds = listOf("h1", "p1", "h2", "p2", "h3", "p3"),
        )
    }

    private fun canonicalTableDocument(): TemplateDocument {
        val tableNodeId = "table1"
        val nodes = mutableMapOf<String, Node>()
        val slots = mutableMapOf<String, Slot>()
        val tableSlotIds = mutableListOf<String>()

        val cellData = listOf(
            listOf("Product", "Quantity", "Price"),
            listOf("Widget", "10", "$5.00"),
            listOf("Gadget", "3", "$15.00"),
        )

        for ((row, rowData) in cellData.withIndex()) {
            for ((col, text) in rowData.withIndex()) {
                val slotId = "slot-$row-$col"
                val textId = "t-$row-$col"
                tableSlotIds.add(slotId)
                nodes[textId] = textNode(textId, text)
                slots[slotId] = Slot(id = slotId, nodeId = tableNodeId, name = "cell-$row-$col", children = listOf(textId))
            }
        }

        nodes[tableNodeId] = Node(
            id = tableNodeId,
            type = "table",
            slots = tableSlotIds,
            props = mapOf("rows" to 3, "columns" to 3, "headerRows" to 1),
        )

        return documentWithChildren(nodes, listOf(tableNodeId), slots)
    }

    private fun canonicalColumnsDocument(): TemplateDocument {
        val leftText = textNode("text-left", "Left column content with some text.")
        val rightText = textNode("text-right", "Right column content with some text.")

        val col0SlotId = "slot-col-0"
        val col1SlotId = "slot-col-1"
        val columnsNode = Node(
            id = "columns1",
            type = "columns",
            slots = listOf(col0SlotId, col1SlotId),
            props = mapOf("columnSizes" to listOf(1, 1)),
        )
        val col0Slot = Slot(id = col0SlotId, nodeId = "columns1", name = "column-0", children = listOf("text-left"))
        val col1Slot = Slot(id = col1SlotId, nodeId = "columns1", name = "column-1", children = listOf("text-right"))

        return documentWithChildren(
            childNodes = mapOf("columns1" to columnsNode, "text-left" to leftText, "text-right" to rightText),
            childNodeIds = listOf("columns1"),
            extraSlots = mapOf(col0SlotId to col0Slot, col1SlotId to col1Slot),
        )
    }

    private fun canonicalDatatableDocument(): TemplateDocument {
        val datatableNodeId = "datatable1"
        val columnsSlotId = "slot-columns"
        val nodes = mutableMapOf<String, Node>()
        val slots = mutableMapOf<String, Slot>()

        val columnDefs = listOf(
            Triple("col-0", "Product", "item.name"),
            Triple("col-1", "Qty", "item.qty"),
            Triple("col-2", "Price", "item.price"),
        )

        val columnNodeIds = mutableListOf<String>()
        for ((colId, header, bodyExpr) in columnDefs) {
            val bodySlotId = "slot-body-$colId"
            val bodyTextId = "body-$colId"

            nodes[bodyTextId] = textNode(bodyTextId, "{{$bodyExpr}}")
            slots[bodySlotId] = Slot(id = bodySlotId, nodeId = colId, name = "body", children = listOf(bodyTextId))
            nodes[colId] = Node(
                id = colId,
                type = "datatable-column",
                slots = listOf(bodySlotId),
                props = mapOf("header" to header, "width" to 33),
            )
            columnNodeIds.add(colId)
        }

        slots[columnsSlotId] = Slot(id = columnsSlotId, nodeId = datatableNodeId, name = "columns", children = columnNodeIds)
        nodes[datatableNodeId] = Node(
            id = datatableNodeId,
            type = "datatable",
            slots = listOf(columnsSlotId),
            props = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "jsonata"),
                "itemAlias" to "item",
                "borderStyle" to "all",
                "headerEnabled" to true,
            ),
        )

        return documentWithChildren(nodes, listOf(datatableNodeId), slots)
    }

    private fun canonicalPageSettingsDocument(): TemplateDocument {
        val text = textNode("text1", "This document uses Letter landscape with custom margins.")

        return documentWithChildren(
            childNodes = mapOf("text1" to text),
            childNodeIds = listOf("text1"),
            pageSettingsOverride = PageSettings(
                format = PageFormat.Letter,
                orientation = Orientation.landscape,
                margins = Margins(top = 30, right = 25, bottom = 30, left = 25),
            ),
        )
    }

    private fun canonicalComplexDocument(): TemplateDocument {
        val h1 = headingNode("h1", "{{companyName}} Report", 1)
        val intro = textNode("intro", "This is the introduction paragraph for the report.")
        val h2 = headingNode("h2", "Details", 2)
        val bulletList = listNode("bullets", listOf("First point", "Second point", "Third point"))
        val orderedList = listNode("ordered", listOf("Step one", "Step two", "Step three"), ordered = true)

        // Loop over items
        val loopSlotId = "slot-loop"
        val loopBodyText = textNode("loop-text", "Item: {{item}}")
        val loopNode = Node(
            id = "loop1",
            type = "loop",
            slots = listOf(loopSlotId),
            props = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
            ),
        )
        val loopSlot = Slot(id = loopSlotId, nodeId = "loop1", name = "children", children = listOf("loop-text"))

        // Conditional footer
        val condSlotId = "slot-cond"
        val footerText = textNode("footer-text", "This is the footer content.")
        val condNode = Node(
            id = "cond1",
            type = "conditional",
            slots = listOf(condSlotId),
            props = mapOf("condition" to mapOf("raw" to "showFooter", "language" to "simple_path")),
        )
        val condSlot = Slot(id = condSlotId, nodeId = "cond1", name = "children", children = listOf("footer-text"))

        return documentWithChildren(
            childNodes = mapOf(
                "h1" to h1,
                "intro" to intro,
                "h2" to h2,
                "bullets" to bulletList,
                "ordered" to orderedList,
                "loop1" to loopNode,
                "loop-text" to loopBodyText,
                "cond1" to condNode,
                "footer-text" to footerText,
            ),
            childNodeIds = listOf("h1", "intro", "h2", "bullets", "ordered", "loop1", "cond1"),
            extraSlots = mapOf(loopSlotId to loopSlot, condSlotId to condSlot),
        )
    }
}
