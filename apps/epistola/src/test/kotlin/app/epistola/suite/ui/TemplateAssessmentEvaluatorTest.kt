// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.templates.TemplateInspectionHandler
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.contracts.model.ContractVersionStatus
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.DataExamples
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.validation.JsonSchemaValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

class TemplateAssessmentEvaluatorTest {
    private val objectMapper = JsonMapper.builder().build()
    private val evaluator = TemplateInspectionHandler(objectMapper, JsonSchemaValidator(objectMapper))

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

    @Test
    fun `only a published contract satisfies contract publication verification`() {
        val base = ContractVersion(
            id = VersionKey.of(1),
            tenantKey = TenantKey.of("tenant"),
            catalogKey = CatalogKey.DEFAULT,
            templateKey = TemplateKey.of("template"),
            schema = null,
            dataModel = null,
            dataExamples = DataExamples.EMPTY,
            status = ContractVersionStatus.DRAFT,
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            publishedAt = null,
        )

        assertThat(evaluator.hasPublishedContract(null)).isFalse()
        assertThat(evaluator.hasPublishedContract(base)).isFalse()
        assertThat(evaluator.hasPublishedContract(base.copy(status = ContractVersionStatus.PUBLISHED))).isTrue()
    }

    @Test
    fun `only a named example that validates against the latest contract is accepted`() {
        val schema = objectMapper.readTree(
            """{"type":"object","properties":{"recipientName":{"type":"string"}},"required":["recipientName"]}""",
        ) as ObjectNode
        val validExample = DataExample(
            id = "valid",
            name = "Full course",
            data = objectMapper.readTree("""{"recipientName":"Ada"}""") as ObjectNode,
        )
        val invalidExample = DataExample(
            id = "invalid",
            name = "Short course",
            data = objectMapper.readTree("""{}""") as ObjectNode,
        )
        val contract = ContractVersion(
            id = VersionKey.of(1),
            tenantKey = TenantKey.of("tenant"),
            catalogKey = CatalogKey.DEFAULT,
            templateKey = TemplateKey.of("template"),
            schema = null,
            dataModel = schema,
            dataExamples = DataExamples.of(validExample, invalidExample),
            status = ContractVersionStatus.DRAFT,
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            publishedAt = null,
        )

        assertThat(evaluator.hasValidNamedExample(contract, "Full course")).isTrue()
        assertThat(evaluator.hasValidNamedExample(contract, "Short course")).isFalse()
        assertThat(evaluator.hasValidNamedExample(contract, "Missing")).isFalse()
        assertThat(evaluator.hasValidNamedExample(null, "Full course")).isFalse()
    }

    @Test
    fun `data contract property supports legacy required and exact schema constraints`() {
        val schema = objectMapper.readTree(
            """{"type":"object","properties":{"recipientName":{"type":"string"},"completionDate":{"type":"string","format":"date"},"trainingHours":{"type":"integer","minimum":1}},"required":["recipientName","completionDate"]}""",
        ) as ObjectNode

        fun predicate(json: String) = objectMapper.readTree(json) as ObjectNode

        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "recipientName",
                predicate("""{"type":"data-contract-property","required":true}"""),
            ),
        ).isTrue()
        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "recipientName",
                predicate("""{"type":"data-contract-property","required":false}"""),
            ),
        ).isFalse()
        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "trainingHours",
                predicate("""{"type":"data-contract-property","required":false,"dataType":"integer","minimum":1}"""),
            ),
        ).isTrue()
        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "completionDate",
                predicate("""{"type":"data-contract-property","required":true,"dataType":"string","format":"date"}"""),
            ),
        ).isTrue()
        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "completionDate",
                predicate("""{"type":"data-contract-property","dataType":"string","format":"date-time"}"""),
            ),
        ).isFalse()
        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "trainingHours",
                predicate("""{"type":"data-contract-property","dataType":"number"}"""),
            ),
        ).isFalse()
        assertThat(
            evaluator.matchesDataContractProperty(
                schema,
                "trainingHours",
                predicate("""{"type":"data-contract-property","minimum":2}"""),
            ),
        ).isFalse()
    }

    @Test
    fun `template expressions are found anywhere and variant selection defaults correctly`() {
        val props = listOf(
            mapOf(
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to "courseTitle"))),
                    ),
                ),
            ),
        )
        assertThat(evaluator.expressions(props)).containsExactly("courseTitle")
        assertThat(evaluator.expressions(props)).doesNotContain("recipientName")

        val default = VariantSummary(VariantKey.of("default"), "Default", emptyMap(), true, true, emptyList())
        val dutch = VariantSummary(VariantKey.of("nld"), "Dutch", emptyMap(), false, true, emptyList())
        assertThat(evaluator.selectVariant(listOf(dutch, default), null)).isEqualTo(default)
        assertThat(evaluator.selectVariant(listOf(default, dutch), "nld")).isEqualTo(dutch)
        assertThat(evaluator.selectVariant(listOf(default), "missing")).isNull()
    }

    @Test
    fun `component and image accessibility predicates inspect persisted editor nodes`() {
        val columns = Node(id = "columns", type = "columns", slots = emptyList(), props = emptyMap())
        val describedImage = Node(
            id = "logo",
            type = "image",
            slots = emptyList(),
            props = mapOf("alt" to "Epistola Training"),
        )
        val decorativeImage = Node(
            id = "rule",
            type = "image",
            slots = emptyList(),
            props = mapOf("decorative" to true),
        )

        assertThat(evaluator.hasComponentType(listOf(columns, describedImage), "columns")).isTrue()
        assertThat(evaluator.hasComponentType(listOf(columns, describedImage), "table")).isFalse()
        assertThat(evaluator.hasImageAccessibility(listOf(describedImage), "described")).isTrue()
        assertThat(evaluator.hasImageAccessibility(listOf(describedImage), "intentional")).isTrue()
        assertThat(evaluator.hasImageAccessibility(listOf(describedImage), "decorative")).isFalse()
        assertThat(evaluator.hasImageAccessibility(listOf(decorativeImage), "decorative")).isTrue()
        assertThat(evaluator.hasImageAccessibility(listOf(decorativeImage), "intentional")).isTrue()
        assertThat(evaluator.hasImageAccessibility(listOf(decorativeImage), "described")).isFalse()
    }
}
