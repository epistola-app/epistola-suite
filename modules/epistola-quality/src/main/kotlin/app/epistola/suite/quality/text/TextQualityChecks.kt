// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.quality.commands.StoreTextResults
import app.epistola.suite.quality.queries.LookupTextResults
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.JsonNodeFactory
import java.security.MessageDigest
import java.util.HexFormat

/** What became of one checker on one variant. */
sealed interface TextCheckOutcome {
    val checker: TextChecker

    /** It answered for every block: [findings] is its full set. [cachedBlocks] of [blocks] needed no call. */
    data class Checked(
        override val checker: TextChecker,
        val findings: List<SubmittedFinding>,
        val blocks: Int,
        val cachedBlocks: Int,
    ) : TextCheckOutcome

    /** It did not apply; its findings stay as they were. */
    data class Skipped(override val checker: TextChecker, val reason: String) : TextCheckOutcome

    /** It could not answer; nothing was cached or submitted, so its earlier findings stay. */
    data class Failed(override val checker: TextChecker, val reason: String) : TextCheckOutcome
}

/**
 * Runs the language checkers over a variant's text through the result cache (ADR 0044):
 *
 * 1. extract the variant's [TextBlock]s;
 * 2. per checker, look up what it reported before for each distinct block under its current
 *    configuration and the locale;
 * 3. ask it only about the blocks it has not seen, and cache its answers;
 * 4. turn every block's diagnostics into findings for the ledger.
 *
 * So a save that changed one paragraph costs one paragraph's check, and a paragraph shared by a
 * hundred templates is checked once. Called by `RunQualityChecks`, which submits the findings.
 */
@Component
class TextQualityChecks {
    fun run(
        subject: QualitySubject,
        blocks: List<TextBlock>,
        locale: ResolvedLocale,
        checkers: List<TextChecker>,
    ): List<TextCheckOutcome> {
        val texts = blocks.associate { it.hash to it.text }
        return checkers.map { checker -> runOne(checker, subject, blocks, texts, locale) }
    }

    private fun runOne(
        checker: TextChecker,
        subject: QualitySubject,
        blocks: List<TextBlock>,
        texts: Map<String, String>,
        locale: ResolvedLocale,
    ): TextCheckOutcome {
        val readiness = try {
            checker.readiness(locale)
        } catch (e: Exception) {
            log.warn("Language checker {} is unavailable: {}", checker.checkerId, e.message)
            return TextCheckOutcome.Failed(checker, "unavailable")
        }
        val configuration = when (readiness) {
            is TextCheckerReadiness.Skipped -> return TextCheckOutcome.Skipped(checker, readiness.reason)
            is TextCheckerReadiness.Ready -> readiness.configuration
        }

        val cached = LookupTextResults(subject.tenantKey, checker.checkerId, configuration, locale.tag, texts.keys).query().byBlockHash
        val missing = texts.keys.filterNot { it in cached }
        val fresh = if (missing.isEmpty()) {
            emptyMap()
        } else {
            val answers = try {
                checker.check(missing.map { texts.getValue(it) }, locale, configuration)
            } catch (e: Exception) {
                log.warn("Language checker {} failed for {}: {}", checker.checkerId, subject.urn, e.message)
                return TextCheckOutcome.Failed(checker, "failed while checking")
            }
            check(answers.size == missing.size) { "${checker.checkerId} answered for ${answers.size} of ${missing.size} texts" }
            missing.zip(answers).toMap().also {
                StoreTextResults(subject.tenantKey, checker.checkerId, configuration, locale.tag, it).execute()
            }
        }

        val findings = TextFindings.of(checker.checkerId, subject, blocks, cached + fresh, configuration)
        return TextCheckOutcome.Checked(checker, findings, blocks = texts.size, cachedBlocks = texts.size - missing.size)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(TextQualityChecks::class.java)
    }
}

/** Language diagnostics as ledger findings: one per diagnostic per block, on the block's node. */
object TextFindings {
    private const val EXCERPT = 40

    fun of(
        checkerId: QualitySourceId,
        subject: QualitySubject,
        blocks: List<TextBlock>,
        byBlockHash: Map<String, List<TextDiagnostic>>,
        configuration: String,
    ): List<SubmittedFinding> {
        val occurrences = mutableMapOf<String, Int>()
        return blocks.flatMap { block ->
            byBlockHash[block.hash].orEmpty()
                // A diagnostic about a placeholder is about text nobody wrote here; one out of range is a checker's bug.
                .filter { block.coversOnlyLiteralText(it.offset, it.length) }
                .map { diagnostic ->
                    val end = diagnostic.offset + diagnostic.length
                    val quote = block.text.substring(diagnostic.offset, end)
                    // Where the text is the problem (a misspelling) it is part of the identity: another typo is
                    // another problem, and must not inherit an ignore. Where the problem is the shape (a long
                    // sentence), rewording it is the same problem and keeps its ignore.
                    val identity = listOf(block.nodeId, diagnostic.ruleId, if (diagnostic.category == TextCategory.READABILITY) "" else quote)
                        .joinToString("|")
                    val occurrence = occurrences.merge(identity, 1, Int::plus)!! - 1
                    SubmittedFinding(
                        ruleId = diagnostic.ruleId,
                        severity = diagnostic.severity,
                        fingerprint = sha256("${checkerId.value}|${subject.urn}|$identity|$occurrence"),
                        message = diagnostic.message,
                        nodeIds = listOf(block.nodeId),
                        docsUrl = diagnostic.docsUrl,
                        context = JsonNodeFactory.instance.objectNode().apply {
                            put("quote", block.display(diagnostic.offset, end))
                            put("before", block.display(diagnostic.offset - EXCERPT, diagnostic.offset))
                            put("after", block.display(end, end + EXCERPT))
                            put("category", diagnostic.category.name)
                            putArray("suggestions").apply { diagnostic.suggestions.forEach { add(it) } }
                        },
                        metadata = JsonNodeFactory.instance.objectNode().put("configuration", configuration),
                    )
                }
        }
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
}
