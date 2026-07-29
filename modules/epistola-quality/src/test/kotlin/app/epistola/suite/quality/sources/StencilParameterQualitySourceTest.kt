// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.sources

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.QualitySubjectType
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.NodeParameterKeys
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

class StencilParameterQualitySourceTest {
    private val source = StencilParameterQualitySource()

    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/default/invoice/v1",
        ignoreScopeUrn = "urn:epistola:template:acme/default/invoice",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("invoice"),
        variantKey = "v1",
    )

    private fun stencilNode(
        schema: Any?,
        bindings: Map<String, String> = emptyMap(),
    ): Node {
        val props = mutableMapOf<String, Any?>(
            StencilNodeKeys.PROP_STENCIL_ID to "greeting",
            StencilNodeKeys.PROP_VERSION to 2,
        )
        if (schema != null) props[StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT] = schema
        if (bindings.isNotEmpty()) props[NodeParameterKeys.PROP_PARAMETER_BINDINGS] = bindings
        return Node(id = "stencil-1", type = StencilNodeKeys.NODE_TYPE, slots = emptyList(), props = props)
    }

    private fun inputFor(node: Node) = QualityCheckInput(
        subject = subject,
        templateModel = TemplateDocument(
            modelVersion = 1,
            root = node.id,
            nodes = mapOf(node.id to node),
            slots = emptyMap(),
            themeRef = ThemeRefInherit(),
        ),
        dataExamples = emptyList(),
        dataModel = null,
    )

    @Test
    fun `an unbound required parameter is an error on the stencil node`() {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("recipientName" to mapOf("type" to "string")),
            "required" to listOf("recipientName"),
        )

        val finding = source.check(inputFor(stencilNode(schema))).single()

        assertThat(finding.ruleId).isEqualTo(StencilParameterQualitySource.RULE_MISSING_REQUIRED_BINDING)
        assertThat(finding.severity).isEqualTo(QualitySeverity.ERROR)
        assertThat(finding.nodeIds).containsExactly("stencil-1")
        assertThat(finding.context["parameters"].size()).isEqualTo(1)
        assertThat(finding.context["parameters"][0].asString()).isEqualTo("recipientName")
    }

    @Test
    fun `a non-blank binding satisfies a required parameter`() {
        val schema = mapOf(
            "properties" to mapOf("recipientName" to mapOf("type" to "string")),
            "required" to listOf("recipientName"),
        )

        assertThat(
            source.check(inputFor(stencilNode(schema, mapOf("recipientName" to "customer.name")))),
        ).isEmpty()
    }

    @Test
    fun `a schema default satisfies a required parameter`() {
        val schema = mapOf(
            "properties" to mapOf(
                "recipientName" to mapOf("type" to "string", "default" to "Customer"),
            ),
            "required" to listOf("recipientName"),
        )

        assertThat(source.check(inputFor(stencilNode(schema)))).isEmpty()
    }

    @Test
    fun `a blank binding remains missing`() {
        val schema = mapOf(
            "properties" to mapOf("recipientName" to mapOf("type" to "string")),
            "required" to listOf("recipientName"),
        )

        assertThat(
            source.check(inputFor(stencilNode(schema, mapOf("recipientName" to "   ")))),
        ).hasSize(1)
    }

    @Test
    fun `an optional unbound parameter is not reported`() {
        val schema = mapOf(
            "properties" to mapOf("recipientName" to mapOf("type" to "string")),
            "required" to emptyList<String>(),
        )

        assertThat(source.check(inputFor(stencilNode(schema)))).isEmpty()
    }

    @Test
    fun `a JsonNode schema snapshot is supported`() {
        val schema = JsonNodeFactory.instance.objectNode().apply {
            putObject("properties").putObject("recipientName").put("type", "string")
            putArray("required").add("recipientName")
        }

        assertThat(source.check(inputFor(stencilNode(schema)))).hasSize(1)
    }

    @Test
    fun `a stencil without a schema snapshot is ignored`() {
        assertThat(source.check(inputFor(stencilNode(schema = null)))).isEmpty()
    }

    @Test
    fun `the same missing parameters fingerprint identically across runs`() {
        val schema = mapOf(
            "properties" to mapOf(
                "first" to mapOf("type" to "string"),
                "second" to mapOf("type" to "string"),
            ),
            "required" to listOf("second", "first"),
        )
        val input = inputFor(stencilNode(schema))

        assertThat(source.check(input).single().fingerprint)
            .isEqualTo(source.check(input).single().fingerprint)
    }

    @Test
    fun `the source does not claim the reserved manual id`() {
        assertThat(source.sourceId).isNotEqualTo(QualitySourceId.MANUAL)
    }
}
