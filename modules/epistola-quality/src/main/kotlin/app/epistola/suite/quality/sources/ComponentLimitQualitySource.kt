// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.sources

import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityFindingSource
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.SubmittedFinding
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reports a document holding more instances of a component than the component registry's
 * `maxInstancesPerDocument` allows — today only the address block, at one.
 *
 * This is a finding, not a save-time refusal (#1028). The editor stops an author inserting a second
 * address block directly, but one can still arrive another way: inside an included stencil (a
 * letterhead that carries its own), over REST or MCP, or by catalog import. Refusing the save would
 * block a template the author has no direct way to fix, so the document is accepted, the renderer
 * uses one address block, and this source says so.
 *
 * Every instance of the component is reported on the one finding, so the editor can mark all of them.
 */
@Component
class ComponentLimitQualitySource : QualityFindingSource {
    override val sourceId = QualitySourceId("layout")

    override val displayName = "Layout"

    override fun check(input: QualityCheckInput): List<SubmittedFinding> = MAX_INSTANCES_PER_DOCUMENT.mapNotNull { (type, limit) ->
        val instances = input.templateModel.nodes.values.filter { it.type == type }.map { it.id }.sorted()
        if (instances.size <= limit) return@mapNotNull null
        SubmittedFinding(
            ruleId = RULE_TOO_MANY_INSTANCES,
            // The document renders, but not as authored: only one instance is used.
            severity = QualitySeverity.WARNING,
            // Keyed on the component type and how many there are: removing one of three is still the
            // same problem but materially changed, so an ignore should be looked at again.
            fingerprint = fingerprint(input.subject.urn, type, instances.size),
            message = "This document holds ${instances.size} '$type' components, but supports at most $limit. " +
                "Only one is used when it renders. Remove the others, or the stencil that brings one in.",
            messageCode = MSG_TOO_MANY_INSTANCES,
            nodeIds = instances,
            context = JsonNodeFactory.instance.objectNode()
                .put("componentType", type)
                .put("limit", limit)
                .put("count", instances.size),
        )
    }

    private fun fingerprint(subjectUrn: String, type: String, count: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$RULE_TOO_MANY_INSTANCES|$subjectUrn|$type|$count".toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val RULE_TOO_MANY_INSTANCES = "layout.too-many-instances"
        const val MSG_TOO_MANY_INSTANCES = "quality.layout.too-many-instances"

        private const val COMPONENT_REGISTRY = "/META-INF/epistola-catalog/component-registry.json"

        /** `type -> maxInstancesPerDocument`, read once from the contract's component registry. */
        internal val MAX_INSTANCES_PER_DOCUMENT: Map<String, Int> by lazy {
            val registry = ComponentLimitQualitySource::class.java.getResourceAsStream(COMPONENT_REGISTRY)
                ?.use { JsonMapper.shared().readTree(it) }
                ?: error("$COMPONENT_REGISTRY is missing from the classpath")
            registry.required("components").values()
                .mapNotNull { component ->
                    component.get("maxInstancesPerDocument")?.asInt()?.let { component.required("type").asString() to it }
                }
                .toMap()
        }
    }
}
