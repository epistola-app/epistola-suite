// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.generation.ProseMirrorConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.expression.JsonataEvaluator
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.properties.UnitValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ColumnsNodeRendererTest {
    private val evaluator = CompositeExpressionEvaluator(jsonataEvaluator = JsonataEvaluator())
    private val converter = ProseMirrorConverter(evaluator)
    private val fontCache = FontCache(pdfaCompliant = false)
    private val renderer = ColumnsNodeRenderer()

    @Test
    fun `V3 removes outer cell padding while preserving the column gap`() {
        val document = columnsDocument()
        val cells = renderCells(document, RenderingDefaults.V3)

        assertPadding(cells[0], top = 0f, right = 4f, bottom = 0f, left = 0f)
        assertPadding(cells[1], top = 0f, right = 0f, bottom = 0f, left = 4f)
    }

    @Test
    fun `V1 retains the historical implicit cell padding`() {
        val document = columnsDocument()
        val cells = renderCells(document, RenderingDefaults.V1)

        assertPadding(cells[0], top = 2f, right = 4f, bottom = 2f, left = 2f)
        assertPadding(cells[1], top = 2f, right = 2f, bottom = 2f, left = 4f)
    }

    private fun columnsDocument(): TemplateDocument {
        val node = Node(
            id = "columns",
            type = "columns",
            slots = listOf("column-0", "column-1"),
        )
        return TemplateDocument(
            root = node.id,
            nodes = mapOf(node.id to node),
            slots = mapOf(
                "column-0" to Slot("column-0", node.id, "column-0", emptyList()),
                "column-1" to Slot("column-1", node.id, "column-1", emptyList()),
            ),
        )
    }

    private fun renderCells(
        document: TemplateDocument,
        defaults: RenderingDefaults,
    ): List<Cell> {
        val context = RenderContext(
            data = emptyMap(),
            expressionEvaluator = evaluator,
            proseMirrorConverter = converter,
            fontCache = fontCache,
            document = document,
            renderingDefaults = defaults,
        )
        val node = document.nodes.getValue(document.root)
        val table = assertIs<Table>(
            renderer.render(node, document, context, NodeRendererRegistry()).single(),
        )
        return table.children.map { assertIs<Cell>(it) }
    }

    private fun assertPadding(
        cell: Cell,
        top: Float,
        right: Float,
        bottom: Float,
        left: Float,
    ) {
        assertEquals(top, cell.getProperty<UnitValue>(Property.PADDING_TOP).value)
        assertEquals(right, cell.getProperty<UnitValue>(Property.PADDING_RIGHT).value)
        assertEquals(bottom, cell.getProperty<UnitValue>(Property.PADDING_BOTTOM).value)
        assertEquals(left, cell.getProperty<UnitValue>(Property.PADDING_LEFT).value)
    }
}
