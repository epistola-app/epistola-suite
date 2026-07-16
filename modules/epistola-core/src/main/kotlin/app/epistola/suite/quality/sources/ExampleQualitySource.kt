package app.epistola.suite.quality.sources

import app.epistola.suite.quality.QualityCheckInput
import app.epistola.suite.quality.QualityFindingSource
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.templates.model.Node
import org.springframework.stereotype.Component
import tools.jackson.databind.node.JsonNodeFactory
import java.security.MessageDigest
import java.util.HexFormat

/**
 * A worked example of a quality source, and the reference for writing another.
 *
 * It is deliberately trivial — two rules over the node graph, no data, no network — because its job
 * is to demonstrate the *seam*, not to be a good check. The interesting checks (readability,
 * grammar, cross-template consistency, compatibility drift) are expected to arrive as separate
 * sources, most of them remote; this one exists so the wiring has a user from day one and so the
 * round trip below is exercised by the demo catalog.
 *
 * ### The round trip it demonstrates
 *
 * 1. An author leaves a text block empty. The sweep runs, this source reports it, the finding opens.
 * 2. The author fills it in. The next run simply *does not report it* — and the ledger resolves it,
 *    because a submission is a full set. Nothing here calls "resolve"; that is the whole point.
 * 3. Had the author instead decided the emptiness was intentional, they ignore it with a reason, and
 *    the ignore rides the fingerprint through every later run and every publish.
 *
 * ### On the fingerprints
 *
 * Both rules fingerprint on `(ruleId, subjectUrn, nodeId)` and deliberately **exclude the text
 * itself**. That is the judgement call every source author has to make, so it is worth spelling out:
 * including the text would mean an author editing a too-long paragraph — but leaving it too long —
 * would get a *new* fingerprint, resurfacing a finding they had already dismissed. The problem
 * ("this block is too long") is identified by which block it is, not by its current wording. Shorten
 * it past the threshold and the finding vanishes by auto-resolve, which is the correct exit.
 *
 * Contrast a rule where the text *is* the problem (a misspelling, say): there the word belongs in
 * the fingerprint, because fixing one typo and introducing another is genuinely a different problem
 * and must not inherit the old ignore.
 */
@Component
class ExampleQualitySource : QualityFindingSource {
    override val sourceId = QualitySourceId("example")

    override val displayName = "Example checks"

    override fun check(input: QualityCheckInput): List<SubmittedFinding> = input.templateModel.nodes.values
        .filter { it.type == TEXT_NODE_TYPE }
        .flatMap { node -> checkTextNode(node, input) }

    private fun checkTextNode(
        node: Node,
        input: QualityCheckInput,
    ): List<SubmittedFinding> {
        val text = extractText(node.props?.get("content"))

        return when {
            text.isBlank() -> listOf(
                SubmittedFinding(
                    ruleId = RULE_EMPTY_TEXT,
                    severity = QualitySeverity.WARNING,
                    fingerprint = fingerprint(RULE_EMPTY_TEXT, input.subject.urn, node.id),
                    message = "This text block is empty and will render as blank space.",
                    // Both rules here are about a single block, so they name one node. A
                    // consistency rule ("these two paragraphs disagree") would name several, and
                    // the editor would mark each of them — that is why this is a list.
                    nodeIds = listOf(node.id),
                ),
            )

            text.length > LONG_TEXT_THRESHOLD -> listOf(
                SubmittedFinding(
                    ruleId = RULE_LONG_TEXT,
                    severity = QualitySeverity.INFO,
                    fingerprint = fingerprint(RULE_LONG_TEXT, input.subject.urn, node.id),
                    message = "This text block is ${text.length} characters long, which is hard to read in a document.",
                    nodeIds = listOf(node.id),
                    context = JsonNodeFactory.instance.objectNode()
                        .put("length", text.length)
                        .put("threshold", LONG_TEXT_THRESHOLD),
                ),
            )

            else -> emptyList()
        }
    }

    /**
     * Collects the visible text out of a ProseMirror document, ignoring its structure. Defensive
     * about shape: a source runs over whatever is stored, including documents written by an older
     * editor, and must return "nothing wrong" rather than throw — a source that throws mid-sweep
     * would take out the tenant's whole run.
     */
    private fun extractText(content: Any?): String {
        val sb = StringBuilder()
        collectText(content, sb)
        return sb.toString().trim()
    }

    private fun collectText(
        node: Any?,
        sb: StringBuilder,
    ) {
        when (node) {
            is Map<*, *> -> {
                (node["text"] as? String)?.let { sb.append(it) }
                collectText(node["content"], sb)
            }

            is List<*> -> node.forEach { collectText(it, sb) }
        }
    }

    private fun fingerprint(
        ruleId: String,
        subjectUrn: String,
        nodeId: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$ruleId|$subjectUrn|$nodeId".toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val RULE_EMPTY_TEXT = "example.empty-text"
        const val RULE_LONG_TEXT = "example.long-text"

        private const val TEXT_NODE_TYPE = "text"

        /** Arbitrary, and openly so — a real readability check would not be a character count. */
        const val LONG_TEXT_THRESHOLD = 600
    }
}
