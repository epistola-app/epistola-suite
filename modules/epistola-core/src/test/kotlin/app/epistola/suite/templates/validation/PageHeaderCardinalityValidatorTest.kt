// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PageHeaderCardinalityValidatorTest {

    private val validator = PageHeaderCardinalityValidator()

    private fun pageHeader(id: String): Node = Node(id = id, type = "pageheader", slots = listOf("$id-slot"), props = mapOf("height" to "40pt"))

    private fun bodyText(id: String): Node = Node(id = id, type = "text", slots = emptyList())

    private fun emptySlot(id: String, nodeId: String): Slot = Slot(id = id, nodeId = nodeId, name = "children", children = emptyList())

    private fun docWithRootChildren(
        children: List<String>,
        extraNodes: Map<String, Node>,
        extraSlots: Map<String, Slot> = emptyMap(),
    ): TemplateDocument {
        val rootSlotId = "root-slot"
        val root = Node(id = "root", type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = "root", name = "children", children = children)
        return TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf("root" to root) + extraNodes,
            slots = mapOf(rootSlotId to rootSlot) + extraSlots,
            themeRef = ThemeRef.Inherit,
        )
    }

    @Test
    fun `no pageheader nodes passes`() {
        val doc = docWithRootChildren(
            children = listOf("body"),
            extraNodes = mapOf("body" to bodyText("body")),
        )
        validator.validate(doc)
    }

    @Test
    fun `single pageheader at root passes`() {
        val doc = docWithRootChildren(
            children = listOf("h1", "body"),
            extraNodes = mapOf(
                "h1" to pageHeader("h1"),
                "body" to bodyText("body"),
            ),
            extraSlots = mapOf("h1-slot" to emptySlot("h1-slot", "h1")),
        )
        validator.validate(doc)
    }

    @Test
    fun `two pageheaders at root pass`() {
        val doc = docWithRootChildren(
            children = listOf("h1", "h2", "body"),
            extraNodes = mapOf(
                "h1" to pageHeader("h1"),
                "h2" to pageHeader("h2"),
                "body" to bodyText("body"),
            ),
            extraSlots = mapOf(
                "h1-slot" to emptySlot("h1-slot", "h1"),
                "h2-slot" to emptySlot("h2-slot", "h2"),
            ),
        )
        validator.validate(doc)
    }

    @Test
    fun `three pageheaders rejected`() {
        val doc = docWithRootChildren(
            children = listOf("h1", "h2", "h3", "body"),
            extraNodes = mapOf(
                "h1" to pageHeader("h1"),
                "h2" to pageHeader("h2"),
                "h3" to pageHeader("h3"),
                "body" to bodyText("body"),
            ),
            extraSlots = mapOf(
                "h1-slot" to emptySlot("h1-slot", "h1"),
                "h2-slot" to emptySlot("h2-slot", "h2"),
                "h3-slot" to emptySlot("h3-slot", "h3"),
            ),
        )
        assertThatThrownBy { validator.validate(doc) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.PAGEHEADER_TOO_MANY)
    }

    @Test
    fun `pageheader nested below a container is rejected`() {
        // root → container → pageheader (not a direct child of root)
        val containerSlotId = "container-slot"
        val doc = docWithRootChildren(
            children = listOf("container"),
            extraNodes = mapOf(
                "container" to Node(id = "container", type = "container", slots = listOf(containerSlotId)),
                "h1" to pageHeader("h1"),
            ),
            extraSlots = mapOf(
                containerSlotId to Slot(id = containerSlotId, nodeId = "container", name = "children", children = listOf("h1")),
                "h1-slot" to emptySlot("h1-slot", "h1"),
            ),
        )
        assertThatThrownBy { validator.validate(doc) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.PAGEHEADER_NOT_AT_ROOT)
    }
}
