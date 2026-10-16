// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

import app.epistola.catalog.validation.TemplateValidationLimits
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class TemplateDocumentValidatorTest {
    private val validator = TemplateDocumentValidator(
        parameterSchemas = NodeParameterSchemaProviderRegistry(emptyList()),
    )

    @Test
    fun `template validation prefixes document-relative errors with templateModel`() {
        val exception = assertThrows<ValidationException> {
            validator.validateTemplate(documentWithMissingRoot())
        }

        assertThat(exception.field).isEqualTo("templateModel.root")
    }

    @Test
    fun `stencil validation prefixes document-relative errors with content`() {
        val exception = assertThrows<ValidationException> {
            validator.validateStencil(documentWithMissingRoot())
        }

        assertThat(exception.field).isEqualTo("content.root")
    }

    @Test
    fun `render validation applies only graph checks with the template field prefix`() {
        val exception = assertThrows<ValidationException> {
            validator.validateTemplateGraphForRendering(documentWithMissingRoot())
        }

        assertThat(exception.field).isEqualTo("templateModel.root")
    }

    @Test
    fun `render validation preserves graph-safe legacy template semantics`() {
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "placeholder" to Node(id = "placeholder", type = "placeholder", props = mapOf("name" to "body")),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("placeholder")),
            ),
        )

        assertThatCode { validator.validateTemplateGraphForRendering(document) }.doesNotThrowAnyException()
        val exception = assertThrows<ValidationException> { validator.validateTemplate(document) }
        assertThat(exception.field).isEqualTo("templateModel.nodes.placeholder")
    }

    @Test
    fun `portable validation golden fixture is published to consumers`() {
        assertThat(
            javaClass.getResource("/META-INF/epistola-catalog/fixtures/v1/template-validation.json"),
        ).isNotNull()
    }

    @Test
    fun `template validation allows five nested stencil instances`() {
        val slugs = Array(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH) { "stencil-$it" }

        assertThatCode {
            validator.validateTemplate(nestedStencils(*slugs))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `template validation rejects the sixth nested stencil instance`() {
        val slugs = Array(TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH + 1) { "stencil-$it" }

        val exception = assertThrows<ValidationException> {
            validator.validateTemplate(nestedStencils(*slugs))
        }

        assertThat(exception.code).isEqualTo(ValidationCode.STENCIL_NESTING_DEPTH_EXCEEDED)
        assertThat(exception.field).isEqualTo("templateModel.nodes.stencil-5.props.stencilKey")
    }

    @Test
    fun `template validation rejects a transitive recursive stencil instance`() {
        val exception = assertThrows<ValidationException> {
            validator.validateTemplate(nestedStencils("address", "contact", "address"))
        }

        assertThat(exception.code).isEqualTo(ValidationCode.STENCIL_RECURSION)
        assertThat(exception.field).isEqualTo("templateModel.nodes.stencil-2.props.stencilKey")
    }

    @Test
    fun `stencil content may contain stencil instances`() {
        // Stencils in stencils are allowed (contract ADR 0006); whether they fit is checked on publish.
        assertDoesNotThrow { validator.validateStencil(nestedStencils("address", "contact")) }
    }

    @Test
    fun `draft validation allows missing required parameter binding but publication validation rejects it`() {
        val schemaRegistry = NodeParameterSchemaProviderRegistry(
            listOf(
                NodeTypeProviderBinding(
                    nodeType = "stencil",
                    provider = NodeParameterSchemaProvider { node, _ ->
                        @Suppress("UNCHECKED_CAST")
                        node.props?.get("parameterSchemaSnapshot") as? Map<String, Any?>
                    },
                ),
            ),
        )
        val parameterAwareValidator = TemplateDocumentValidator(
            parameterSchemas = schemaRegistry,
        )
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "stencil" to Node(
                    id = "stencil",
                    type = "stencil",
                    slots = listOf("stencil-slot"),
                    props = mapOf(
                        "stencilKey" to "header",
                        "parameterSchemaSnapshot" to mapOf(
                            "type" to "object",
                            "properties" to mapOf("recipientName" to mapOf("type" to "string")),
                            "required" to listOf("recipientName"),
                        ),
                    ),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil")),
                "stencil-slot" to Slot("stencil-slot", "stencil", "children", emptyList()),
            ),
        )

        assertThatCode {
            parameterAwareValidator.validateTemplateDraft(document)
        }.doesNotThrowAnyException()
        val exception = assertThrows<ValidationException> {
            parameterAwareValidator.validateTemplate(document)
        }
        assertThat(exception.field)
            .isEqualTo("templateModel.nodes.stencil.props.parameterBindings.recipientName")
    }

    @Test
    fun `two address blocks are accepted on save and reported as a quality finding instead`() {
        // #1028: one can arrive inside an included stencil, which the author cannot fix from the
        // template, so the epistola-quality "layout" source reports it rather than the save refusing.
        val addressBlock = { id: String ->
            Node(id = id, type = "addressblock", slots = listOf("$id-address", "$id-aside"))
        }
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "own" to addressBlock("own"),
                "from-stencil" to addressBlock("from-stencil"),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("own", "from-stencil")),
            ) + listOf("own", "from-stencil").flatMap { id ->
                listOf("address", "aside").map { name -> "$id-$name" to Slot(id = "$id-$name", nodeId = id, name = name, children = emptyList()) }
            },
        )

        assertThatCode { validator.validateTemplate(document) }.doesNotThrowAnyException()
    }

    @Test
    fun `a javascript expression is rejected on every validation entry point`() {
        val document = documentWithNode(
            Node(id = "cond", type = "conditional", props = mapOf("condition" to mapOf("raw" to "while(true){}", "language" to "javascript"))),
        )

        val entryPoints: List<Pair<String, (TemplateDocument) -> Unit>> = listOf(
            "templateModel" to validator::validateTemplate,
            "templateModel" to validator::validateTemplateDraft,
            "templateModel" to validator::validateTemplatePublishable,
            "templateModel" to validator::validateTemplateGraphForRendering,
            "content" to validator::validateStencil,
        )
        for ((prefix, validate) in entryPoints) {
            val exception = assertThrows<ValidationException> { validate(document) }
            assertThat(exception.field).isEqualTo("$prefix.nodes.cond.props.condition.language")
            assertThat(exception.code).isEqualTo(ValidationCode.TEMPLATE_EXPRESSION_INVALID)
            assertThat(exception.message).contains("JavaScript expressions are no longer supported").contains("JSONata")
        }
    }

    @Test
    fun `javascript is found in inline rich-text expressions and rich-text variable bindings`() {
        val inline = documentWithNode(
            Node(
                id = "text",
                type = "text",
                props = mapOf(
                    "content" to mapOf(
                        "type" to "doc",
                        "content" to listOf(
                            mapOf(
                                "type" to "paragraph",
                                "content" to listOf(
                                    mapOf("type" to "expression", "attrs" to mapOf("expression" to "name", "language" to "JavaScript")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertThat(assertThrows<ValidationException> { validator.validateTemplateDraft(inline) }.field)
            .isEqualTo("templateModel.nodes.text.props.content.content[0].content[0].attrs.language")

        val binding = documentWithNode(
            Node(id = "rt", type = "richTextVariable", props = mapOf("binding" to "body", "language" to "javascript")),
        )
        assertThat(assertThrows<ValidationException> { validator.validateTemplateDraft(binding) }.field)
            .isEqualTo("templateModel.nodes.rt.props.language")
    }

    @Test
    fun `jsonata and simple path expressions are not affected`() {
        val document = documentWithNode(
            Node(id = "cond", type = "conditional", props = mapOf("condition" to mapOf("raw" to "paid", "language" to "jsonata"))),
        )
        assertThatCode { validator.validateTemplateDraft(document) }.doesNotThrowAnyException()
        assertThat(JavaScriptExpressionScanner.javaScriptExpressionPaths(document)).isEmpty()
    }

    private fun documentWithNode(node: Node) = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot")), node.id to node),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf(node.id))),
    )

    private fun documentWithMissingRoot() = TemplateDocument(
        root = "missing",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
    )

    private fun nestedStencils(vararg slugs: String): TemplateDocument {
        val stencils = slugs.mapIndexed { index, slug ->
            Node(
                id = "stencil-$index",
                type = "stencil",
                slots = listOf("stencil-$index-children"),
                props = mapOf("stencilKey" to slug),
            )
        }
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node("root", "root", listOf("root-children"))) +
                stencils.associateBy(Node::id),
            slots = mapOf(
                "root-children" to Slot(
                    "root-children",
                    "root",
                    "children",
                    listOf(stencils.first().id),
                ),
            ) + stencils.mapIndexed { index, stencil ->
                val child = stencils.getOrNull(index + 1)
                "stencil-$index-children" to Slot(
                    "stencil-$index-children",
                    stencil.id,
                    "children",
                    child?.let { listOf(it.id) }.orEmpty(),
                )
            },
        )
    }
}
