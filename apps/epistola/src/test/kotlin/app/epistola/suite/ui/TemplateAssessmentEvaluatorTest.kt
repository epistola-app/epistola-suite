// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.templates.TemplateInspectionHandler
import app.epistola.suite.templates.model.VariantSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class TemplateAssessmentEvaluatorTest {
    private val evaluator = TemplateInspectionHandler(JsonMapper.builder().build())

    @Test
    fun `required properties include nested paths but not optional properties`() {
        val schema = JsonMapper.builder().build().readTree("""{"properties":{"recipient":{"type":"object","properties":{"name":{"type":"string"},"note":{"type":"string"}},"required":["name"]},"optional":{"type":"string"}},"required":["recipient"]}""") as ObjectNode
        assertThat(evaluator.requiredFields(schema)).containsExactlyInAnyOrder("recipient", "recipient.name")
    }

    @Test
    fun `only expressions inside headings satisfy heading verification`() {
        val props = mapOf(
            "content" to listOf(
                mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to "wrong")))),
                mapOf("type" to "heading", "content" to listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to "recipientName")))),
            ),
        )
        assertThat(evaluator.headingExpressions(listOf(props))).containsExactly("recipientName")
    }

    @Test
    fun `only a published default variant satisfies publication verification`() {
        val draftDefault = VariantSummary(VariantKey.of("default"), "Default", emptyMap(), true, true, emptyList())
        val publishedNonDefault = VariantSummary(VariantKey.of("other"), "Other", emptyMap(), false, false, listOf(1))
        val publishedDefault = draftDefault.copy(hasDraft = false, publishedVersions = listOf(1))

        assertThat(evaluator.hasPublishedDefaultVariant(listOf(draftDefault, publishedNonDefault))).isFalse()
        assertThat(evaluator.hasPublishedDefaultVariant(listOf(publishedDefault))).isTrue()
    }
}
