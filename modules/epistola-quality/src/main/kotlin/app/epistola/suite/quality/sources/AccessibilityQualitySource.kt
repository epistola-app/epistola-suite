// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.sources

import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityFindingSource
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.templates.model.Node
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Accessibility rules over the node graph — the first source that finds something genuinely worth
 * finding rather than demonstrating the seam.
 *
 * Its claim to exist is that the renderer already makes one on our behalf. Every render is tagged
 * and stamped with a **PDF/UA-1 identifier**, and `ImageNodeRenderer` honours the contract on the
 * rendering side: `decorative` images become artifacts, and an `alt` becomes the image's alternate
 * description (WCAG PDF1/PDF4). What nothing does is tell the author when they have supplied
 * neither — so the document ships asserting conformance it does not have, and the only person who
 * finds out is a reader with a screen reader.
 *
 * Needs nothing but the template model, which is why it is here rather than waiting on the widened
 * input: the checks that need the resolved theme or an asset's dimensions genuinely have to wait,
 * and this one does not.
 */
@Component
class AccessibilityQualitySource : QualityFindingSource {
    override val sourceId = QualitySourceId("accessibility")

    override val displayName = "Accessibility"

    override fun check(input: QualityCheckInput): List<SubmittedFinding> = input.templateModel.nodes.values
        .filter { it.type == IMAGE_NODE_TYPE }
        .mapNotNull { node -> checkImage(node, input) }

    private fun checkImage(
        node: Node,
        input: QualityCheckInput,
    ): SubmittedFinding? {
        val props = node.props ?: return null

        // A decorative image is deliberately silent — the renderer marks it as an artifact, which is
        // the correct answer for a divider or a background flourish, not an omission to report.
        //
        // `== true` rather than `as? Boolean == true`: props is a Java Map<String, Object>, so a
        // value is a Kotlin platform type, and the redundant cast is what CodeQL reads as a possible
        // null deref. Structural equality is null-safe and means exactly the same thing — false for
        // null, a non-Boolean, or Boolean false; true only for boxed true.
        if (props["decorative"] == true) return null
        if (!(props["alt"] as? String).isNullOrBlank()) return null

        return SubmittedFinding(
            ruleId = RULE_IMAGE_MISSING_ALT,
            // A warning, not an error: the document renders and reads correctly for most people, so
            // it is not broken. But it is a real defect for anyone using a screen reader, and it
            // makes the PDF/UA-1 identifier the renderer stamps untrue — INFO would file that under
            // "nice to have", which it is not.
            severity = QualitySeverity.WARNING,
            fingerprint = fingerprint(RULE_IMAGE_MISSING_ALT, input.subject.urn, node.id),
            message = "This image has no alt text, so a screen reader announces nothing in its place. " +
                "Describe it, or mark it decorative if it carries no meaning.",
            nodeIds = listOf(node.id),
            docsUrl = WCAG_IMAGE_ALT_URL,
        )
    }

    /**
     * Keyed on which image it is, never on the alt text.
     *
     * The problem is "this image is unlabelled" — identified by the node, not by what is currently
     * (not) written in it. Supply an alt and the rule stops reporting it, which is what resolves it;
     * there is no edit that leaves it unlabelled *differently* and so no reason for it to resurface.
     */
    private fun fingerprint(
        ruleId: String,
        subjectUrn: String,
        nodeId: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$ruleId|$subjectUrn|$nodeId".toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val RULE_IMAGE_MISSING_ALT = "a11y.image-missing-alt"

        private const val IMAGE_NODE_TYPE = "image"
        private const val WCAG_IMAGE_ALT_URL = "https://www.w3.org/WAI/WCAG22/Techniques/pdf/PDF1"
    }
}
