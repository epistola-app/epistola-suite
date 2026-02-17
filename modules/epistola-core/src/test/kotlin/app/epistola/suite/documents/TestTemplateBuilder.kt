package app.epistola.suite.documents

import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import java.util.UUID

/**
 * Helper to build minimal TemplateDocument instances for testing.
 * Creates documents with a root node and an empty children slot - suitable
 * for tests that only need valid template structure without actual content.
 *
 * Note: Generated PDFs from these documents will be valid but contain no visible content.
 * If your test needs actual content, add nodes to the document.
 */
object TestTemplateBuilder {
    fun buildMinimal(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Template",
    ): TemplateDocument {
        val rootId = "root-$id"
        val slotId = "slot-$id"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to Node(
                    id = rootId,
                    type = "root",
                    slots = listOf(slotId),
                ),
            ),
            slots = mapOf(
                slotId to Slot(
                    id = slotId,
                    nodeId = rootId,
                    name = "children",
                    children = emptyList(),
                ),
            ),
            themeRef = ThemeRef.Inherit,
        )
    }
}
