// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.sources

import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityFindingSource
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.templates.model.NodeParameterKeys
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reports stencil instances that cannot supply every required parameter.
 *
 * This deliberately mirrors [app.epistola.catalog.validation.TemplateValidator]:
 * a required parameter is satisfied by either a non-blank binding or a schema default. Validation
 * protects writes, while this source makes persisted problems from bulk upgrades, imports, or
 * legacy documents visible in the quality report and on the affected editor node.
 */
@Component
class StencilParameterQualitySource : QualityFindingSource {
    override val sourceId = QualitySourceId("stencil-parameters")

    override val displayName = "Stencil parameters"

    override fun check(input: QualityCheckInput): List<SubmittedFinding> = input.templateModel.nodes.values
        .filter { it.type == StencilNodeKeys.NODE_TYPE }
        .mapNotNull { node ->
            val props = node.props ?: return@mapNotNull null
            val schema = parameterSchema(props[StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT])
                ?: return@mapNotNull null
            val bindings = parameterBindings(props[NodeParameterKeys.PROP_PARAMETER_BINDINGS])
            val missing = schema.required
                .filter { name -> bindings[name].isNullOrBlank() && name !in schema.defaulted }
                .sorted()
            if (missing.isEmpty()) return@mapNotNull null

            val parameterLabel = if (missing.size == 1) "parameter" else "parameters"
            SubmittedFinding(
                ruleId = RULE_MISSING_REQUIRED_BINDING,
                severity = QualitySeverity.ERROR,
                fingerprint = fingerprint(
                    RULE_MISSING_REQUIRED_BINDING,
                    input.subject.urn,
                    node.id,
                    missing,
                ),
                message = "This stencil has required $parameterLabel without a binding or default: " +
                    missing.joinToString(", ") + ".",
                nodeIds = listOf(node.id),
                context = JsonNodeFactory.instance.objectNode().apply {
                    put("stencilId", props[StencilNodeKeys.PROP_STENCIL_ID] as? String)
                    putArray("parameters").apply { missing.forEach(::add) }
                },
            )
        }

    private data class ParameterSchema(
        val required: Set<String>,
        val defaulted: Set<String>,
    )

    private fun parameterSchema(raw: Any?): ParameterSchema? = when (raw) {
        is Map<*, *> -> {
            val properties = raw["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val required = (raw["required"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
            val defaulted = properties.entries
                .filter { (_, property) -> hasDefault(property) }
                .mapNotNull { (name, _) -> name as? String }
                .toSet()
            ParameterSchema(required, defaulted)
        }

        is ObjectNode -> {
            val properties = raw["properties"] as? ObjectNode
            val required = raw["required"]
                ?.takeIf(JsonNode::isArray)
                ?.mapNotNull { it.takeIf(JsonNode::isString)?.asString() }
                ?.toSet()
                .orEmpty()
            val defaulted = properties
                ?.propertyNames()
                ?.asSequence()
                ?.filter { name -> properties[name]?.has("default") == true }
                ?.toSet()
                .orEmpty()
            ParameterSchema(required, defaulted)
        }

        else -> null
    }

    private fun hasDefault(property: Any?): Boolean = when (property) {
        is Map<*, *> -> property.containsKey("default")
        is ObjectNode -> property.has("default")
        else -> false
    }

    private fun parameterBindings(raw: Any?): Map<String, String> = when (raw) {
        is Map<*, *> -> raw.entries.mapNotNull { (name, value) ->
            val key = name as? String ?: return@mapNotNull null
            val expression = value as? String ?: return@mapNotNull null
            key to expression
        }.toMap()

        is ObjectNode -> raw.properties()
            .asSequence()
            .mapNotNull { (name, value) -> value.takeIf(JsonNode::isString)?.asString()?.let { name to it } }
            .toMap()

        else -> emptyMap()
    }

    private fun fingerprint(
        ruleId: String,
        subjectUrn: String,
        nodeId: String,
        missing: List<String>,
    ): String {
        val evidence = missing.joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ruleId|$subjectUrn|$nodeId|$evidence".toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val RULE_MISSING_REQUIRED_BINDING = "stencils.missing-required-parameter-binding"
    }
}
