package app.epistola.suite.quality.sources

import app.epistola.catalog.protocol.FontRef
import app.epistola.suite.catalog.DependencyScanner
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
 * Reports a text style that names a font the installation cannot resolve.
 *
 * When a `fontFamily` names a family that ships no usable face, the renderer does not fail — it
 * silently falls back to a default font, so the document renders in the wrong typeface and nobody is
 * told. That is a real defect an author wants to know about, and one they cannot see from the editor
 * preview, which falls back the same silent way.
 *
 * The source stays pure: whether a family resolves is a database question (it reads `font_variants`),
 * so the caller resolves every referenced font once and hands the answers in via
 * [QualityCheckInput.dependencies]. This source only reads the node graph and looks the answer up —
 * which is exactly the "caller pre-resolves, source stays pure" seam the next DB-backed sources
 * reuse.
 *
 * It reports only fonts the map marks **resolved = false**. A font absent from the map is unknown,
 * not unresolved, and stays silent — a gap in the caller's resolution must never manufacture a
 * finding.
 */
@Component
class UnresolvedFontQualitySource : QualityFindingSource {
    override val sourceId = QualitySourceId("fonts")

    override val displayName = "Fonts"

    override val requirements = setOf(QualityDataRequirement.RESOLVED_TEMPLATE_DEPENDENCIES)

    override fun check(input: QualityCheckInput): List<SubmittedFinding> = buildList {
        // A font named on the whole document, not on any one node — reported without a node marker.
        DependencyScanner.fontRefIn(input.templateModel.documentStylesOverride)
            ?.takeIf { input.isUnresolved(it) }
            ?.let { add(finding(it, DOCUMENT_ANCHOR, emptyList(), input)) }

        // A font named on a specific node — reported on that node, so the editor can mark it.
        for (node in input.templateModel.nodes.values) {
            val ref = DependencyScanner.fontRefIn(node.styles) ?: continue
            if (input.isUnresolved(ref)) add(finding(ref, node.id, listOf(node.id), input))
        }
    }

    private fun QualityCheckInput.isUnresolved(ref: FontRef): Boolean = dependencies.fonts[ref] == false

    private fun finding(
        ref: FontRef,
        anchor: String,
        nodeIds: List<String>,
        input: QualityCheckInput,
    ): SubmittedFinding {
        val where = ref.catalogKey?.let { "$it/${ref.slug}" } ?: ref.slug
        return SubmittedFinding(
            ruleId = RULE_UNRESOLVED_FONT,
            // Wrong typeface, not a broken document — but it is wrong, and silently so, which is
            // worse than a visible error. A warning, matching the alt-text rule's reasoning.
            severity = QualitySeverity.WARNING,
            // Keyed on which node and which font — changing the font, even to another missing one,
            // is a materially different problem and should resurface for a fresh look; fixing it so
            // the font resolves stops the report and auto-resolves the finding.
            fingerprint = fingerprint(RULE_UNRESOLVED_FONT, input.subject.urn, anchor, where),
            message = "The font \"${ref.slug}\" is not available, so this text falls back to a default " +
                "typeface. Add the font, or change the style to one that is installed.",
            messageCode = MSG_UNRESOLVED_FONT,
            nodeIds = nodeIds,
            context = JsonNodeFactory.instance.objectNode()
                .put("slug", ref.slug)
                .apply { ref.catalogKey?.let { put("catalogKey", it) } },
        )
    }

    private fun fingerprint(
        ruleId: String,
        subjectUrn: String,
        anchor: String,
        fontKey: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ruleId|$subjectUrn|$anchor|$fontKey".toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val RULE_UNRESOLVED_FONT = "fonts.unresolved-family"
        const val MSG_UNRESOLVED_FONT = "quality.fonts.unresolved-family"

        /** Fingerprint anchor for a document-level font, which has no node id. */
        private const val DOCUMENT_ANCHOR = "document"
    }
}
