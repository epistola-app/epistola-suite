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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class TemplateDocumentGraphValidatorTest {
    private val validator = TemplateDocumentGraphValidator()

    @Test
    fun `valid document passes`() {
        validator.validate(doc(children = listOf("body"), extraNodes = mapOf("body" to leaf("body"))))
    }

    @Test
    fun `root must be the document root component`() {
        assertThatThrownBy {
            validator.validate(doc(rootNode = Node("root", "container", listOf("root-slot"))))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `missing root is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(root = "missing"))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `node key mismatch is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(extraNodes = mapOf("body-key" to leaf("body"))))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `missing slot is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(rootNode = Node("root", "root", listOf("missing-slot")), slots = emptyMap()))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `slot owner mismatch is rejected`() {
        assertThatThrownBy {
            validator.validate(
                doc(
                    children = listOf("body"),
                    extraNodes = mapOf("body" to leaf("body")),
                    slots = mapOf("root-slot" to Slot("root-slot", "body", "children", listOf("body"))),
                ),
            )
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `missing child is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(children = listOf("missing-child")))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `duplicate parent is rejected`() {
        val container = Node("container", "container", listOf("container-slot"))
        assertThatThrownBy {
            validator.validate(
                doc(
                    children = listOf("body", "container"),
                    extraNodes = mapOf("body" to leaf("body"), "container" to container),
                    extraSlots = mapOf("container-slot" to Slot("container-slot", "container", "children", listOf("body"))),
                ),
            )
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `duplicate slot reference on a node is rejected`() {
        assertThatThrownBy {
            validator.validate(
                doc(rootNode = Node("root", "root", listOf("root-slot", "root-slot"))),
            )
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
            .hasMessageContaining("more than once")
    }

    @Test
    fun `duplicate child reference in a slot is rejected`() {
        assertThatThrownBy {
            validator.validate(
                doc(
                    children = listOf("body", "body"),
                    extraNodes = mapOf("body" to leaf("body")),
                ),
            )
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
            .hasMessageContaining("more than once")
    }

    @Test
    fun `graph errors use paths relative to the document`() {
        val exception = org.junit.jupiter.api.assertThrows<ValidationException> {
            validator.validate(doc(root = "missing"))
        }

        assertThat(exception.field).isEqualTo("root")
    }

    @Test
    fun `root as child cycle is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(children = listOf("root")))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `unreachable node is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(extraNodes = mapOf("orphan" to leaf("orphan"))))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `unknown node type is rejected`() {
        assertThatThrownBy {
            validator.validate(doc(children = listOf("body"), extraNodes = mapOf("body" to Node("body", "unknown-component", emptyList()))))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_NODE_TYPE_UNSUPPORTED)
    }

    @Test
    fun `oversized graph is rejected`() {
        val nodes = (1..(TemplateDocumentGraphValidator.MAX_NODES + 1)).associate { index ->
            "body-$index" to leaf("body-$index")
        }
        assertThatThrownBy {
            validator.validate(doc(children = nodes.keys.toList(), extraNodes = nodes))
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `oversized child edge list is rejected before traversal`() {
        assertThatThrownBy {
            validator.validate(
                doc(children = List(TemplateDocumentGraphValidator.MAX_NODES + 1) { "body-$it" }),
            )
        }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
            .hasMessageContaining("child references")
    }

    private fun doc(
        root: String = "root",
        rootNode: Node = Node("root", "root", listOf("root-slot")),
        children: List<String> = emptyList(),
        extraNodes: Map<String, Node> = emptyMap(),
        slots: Map<String, Slot>? = null,
        extraSlots: Map<String, Slot> = emptyMap(),
    ): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = root,
        nodes = mapOf(rootNode.id to rootNode) + extraNodes,
        slots = slots ?: (mapOf("root-slot" to Slot("root-slot", rootNode.id, "children", children)) + extraSlots),
        themeRef = ThemeRef.Inherit,
    )

    private fun leaf(id: String): Node = Node(id, "text", emptyList())
}
