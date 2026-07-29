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
        assertThat(exception.field).isEqualTo("templateModel.nodes.stencil-5.props.stencilId")
    }

    @Test
    fun `template validation rejects a transitive recursive stencil instance`() {
        val exception = assertThrows<ValidationException> {
            validator.validateTemplate(nestedStencils("address", "contact", "address"))
        }

        assertThat(exception.code).isEqualTo(ValidationCode.STENCIL_RECURSION)
        assertThat(exception.field).isEqualTo("templateModel.nodes.stencil-2.props.stencilId")
    }

    @Test
    fun `Suite capability policy rejects embedded stencil references`() {
        val exception = assertThrows<ValidationException> {
            validator.validateStencil(nestedStencils("address", "contact"))
        }

        assertThat(exception.code).isEqualTo(ValidationCode.GENERIC)
        assertThat(exception.field).isEqualTo("content")
        assertThat(exception.message).isEqualTo(
            "Stencil content cannot contain nested stencil components. " +
                "Stencil nodes found: stencil-0, stencil-1",
        )
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
                        "stencilId" to "header",
                        "version" to 1,
                        "draftVersion" to 2,
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
                props = mapOf("stencilId" to slug, "version" to 1),
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
