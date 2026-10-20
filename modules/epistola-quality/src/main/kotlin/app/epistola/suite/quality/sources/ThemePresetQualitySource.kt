// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.sources

import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityDataRequirement
import app.epistola.suite.quality.QualityFindingSource
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.SubmittedFinding
import org.springframework.stereotype.Component
import tools.jackson.databind.node.JsonNodeFactory
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reports a block that uses a preset the theme it renders with does not style (contract ADR 0007).
 *
 * A catalog declares its presets and each theme gives them their look. A theme that leaves one out
 * renders the block with its own styles only, without a word, so the author learns of it here.
 * Pure: the caller resolves which presets the theme styles ([ThemePresets][app.epistola.suite.quality.ThemePresets]).
 */
@Component
class ThemePresetQualitySource : QualityFindingSource {
    override val sourceId = QualitySourceId("theme-presets")

    override val displayName = "Theme presets"

    override val requirements = setOf(QualityDataRequirement.RESOLVED_TEMPLATE_DEPENDENCIES)

    override fun check(input: QualityCheckInput): List<SubmittedFinding> {
        val theme = input.dependencies.themePresets ?: return emptyList()
        return input.templateModel.nodes.values.mapNotNull { node ->
            val preset = node.stylePreset?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (preset in theme.styled) return@mapNotNull null
            SubmittedFinding(
                ruleId = RULE_UNSTYLED_PRESET,
                severity = QualitySeverity.WARNING,
                fingerprint = fingerprint(input.subject.urn, node.id, preset, theme.themeName.orEmpty()),
                message = if (theme.themeName != null) {
                    "This block uses the preset \"$preset\", which the theme \"${theme.themeName}\" does not style, so it shows with its own styles only."
                } else {
                    "This block uses the preset \"$preset\", but no theme applies to this template, so it shows with its own styles only."
                },
                messageCode = MSG_UNSTYLED_PRESET,
                nodeIds = listOf(node.id),
                context = JsonNodeFactory.instance.objectNode().put("preset", preset).apply { theme.themeName?.let { put("theme", it) } },
            )
        }
    }

    private fun fingerprint(subjectUrn: String, nodeId: String, preset: String, theme: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest("$RULE_UNSTYLED_PRESET|$subjectUrn|$nodeId|$preset|$theme".toByteArray()),
    )

    companion object {
        const val RULE_UNSTYLED_PRESET = "theme-presets.unstyled"
        const val MSG_UNSTYLED_PRESET = "quality.theme-presets.unstyled"
    }
}
