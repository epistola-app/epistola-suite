// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

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

    private fun documentWithMissingRoot() = TemplateDocument(
        root = "missing",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
    )
}
