// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/**
 * A rule as the built-in engine runs it (ADR 0046): a profile rule's id, title, intent and severity,
 * and its `builtin` extra, [check]. The engine knows the check types; the rule supplies the
 * parameters.
 */
data class BuiltinRule(
    val id: String,
    val title: String,
    val intent: String,
    val severity: QualitySeverity,
    val check: BuiltinCheck,
)

/** The check types the built-in engine implements, each with its parameters: a rule's `builtin` extra. */
sealed interface BuiltinCheck {
    /** A word twice in a row, except the ones a language repeats on purpose, per language. */
    data class RepeatedWord(val allowed: Map<String, Set<String>> = emptyMap()) : BuiltinCheck

    /** A sentence of more than [limit] words; a placeholder counts as one. */
    data class MaxSentenceWords(val limit: Int) : BuiltinCheck

    /** Two or more spaces between words. */
    data object DoubleSpace : BuiltinCheck

    /** A space before `, . ; : ! ?`, except in the languages that put one there. */
    data class SpaceBeforePunctuation(val exceptLanguages: Set<String> = emptySet()) : BuiltinCheck

    /** Words or phrases to avoid, per language, each with the words to use instead. */
    data class WordList(val entries: Map<String, Map<String, List<String>>>) : BuiltinCheck
}

/**
 * The built-in engine (provider kind `builtin`, ADR 0047): in-process, always available, and on every
 * trigger, so some checking works on every installation.
 *
 * It runs [rules]. Until quality profiles exist (ADR 0046) these are [BuiltinRuleSets.CLEAR_LANGUAGE],
 * the rules the default profile will hold; a profile then supplies them instead, and the engine does
 * not change. Every diagnostic names its rule and carries the rule's intent as its explanation.
 *
 * Messages are English for now, like the rest of the quality report.
 */
@Component
class BuiltinRulesChecker(
    private val rules: List<BuiltinRule>,
) : TextChecker {
    /**
     * The constructor Spring uses. Explicit, because Spring would read a `List<BuiltinRule>` parameter
     * as "every BuiltinRule bean" and inject an empty list.
     */
    @Autowired
    constructor() : this(BuiltinRuleSets.CLEAR_LANGUAGE)

    override val checkerId = QualitySourceId("builtin")

    override val displayName = "Built-in rules"

    override val egress = TextEgress.IN_PROCESS

    override val triggers: Set<CheckTrigger> = CheckTrigger.entries.toSet()

    /** The rules' own hash is in the key, so a changed rule is a cache miss, never an old answer. */
    private val rulesHash: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rules.toString().toByteArray())).take(16)

    override fun readiness(locale: ResolvedLocale): TextCheckerReadiness = TextCheckerReadiness.Ready("$ENGINE_VERSION/$rulesHash/${language(locale.tag)}")

    override fun check(
        texts: List<String>,
        locale: ResolvedLocale,
        configuration: String,
    ): List<List<TextDiagnostic>> {
        val language = language(locale.tag)
        return texts.map { text -> rules.flatMap { rule -> apply(rule, text, language) } }
    }

    private fun apply(rule: BuiltinRule, text: String, language: String): List<TextDiagnostic> {
        fun diagnostic(category: TextCategory, message: String, range: IntRange, suggestions: List<String> = emptyList()) = TextDiagnostic(
            ruleId = rule.id,
            category = category,
            severity = rule.severity,
            message = message,
            offset = range.first,
            length = range.last - range.first + 1,
            suggestions = suggestions,
            explanation = rule.intent,
        )
        return when (val check = rule.check) {
            is BuiltinCheck.RepeatedWord -> REPEATED_WORD.findAll(text)
                .filter { it.groupValues[1].lowercase() !in check.allowed[language].orEmpty() }
                .map { diagnostic(TextCategory.GRAMMAR, "“${it.groupValues[1]}” appears twice in a row.", it.range, listOf(it.groupValues[1])) }
                .toList()

            is BuiltinCheck.MaxSentenceWords -> SENTENCE.findAll(text).mapNotNull { match ->
                val sentence = match.value.trimEnd()
                val words = WORD.findAll(sentence).count()
                if (words <= check.limit) return@mapNotNull null
                val leading = sentence.length - sentence.trimStart().length
                diagnostic(
                    TextCategory.READABILITY,
                    "This sentence has $words words; the limit is ${check.limit}. Consider splitting it.",
                    (match.range.first + leading) until (match.range.first + sentence.length),
                )
            }.toList()

            BuiltinCheck.DoubleSpace -> DOUBLE_SPACE.findAll(text)
                .map { diagnostic(TextCategory.TYPOGRAPHY, "Two or more spaces between words.", it.range, listOf(" ")) }
                .toList()

            is BuiltinCheck.SpaceBeforePunctuation -> if (language in check.exceptLanguages) {
                emptyList()
            } else {
                SPACE_BEFORE_PUNCTUATION.findAll(text)
                    .map { diagnostic(TextCategory.TYPOGRAPHY, "A space before a punctuation mark.", it.range, listOf("")) }
                    .toList()
            }

            is BuiltinCheck.WordList -> check.entries[language].orEmpty().flatMap { (phrase, alternatives) ->
                phrasePattern(phrase).findAll(text).map { match ->
                    diagnostic(
                        TextCategory.STYLE,
                        "“${match.value}”: plain language prefers ${alternatives.joinToString(" or ") { "“$it”" }}.",
                        match.range,
                        alternatives.map { matchCase(it, match.value) },
                    )
                }.toList()
            }
        }
    }

    companion object {
        /** Part of every result's cache key, beside the rules' hash: raise it when a check type changes. */
        const val ENGINE_VERSION = "builtin/1"

        private fun language(tag: String): String = Locale.forLanguageTag(tag).language.ifBlank { "und" }

        private fun matchCase(replacement: String, original: String): String = if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { it.titlecase(Locale.ROOT) }
        } else {
            replacement
        }

        private fun phrasePattern(phrase: String) = Regex(
            "(?<![\\p{L}\\p{N}])" + phrase.split(' ').joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

        private val REPEATED_WORD = Regex("(?<![\\p{L}\\p{N}])(\\p{L}+)\\s+(\\1)(?![\\p{L}\\p{N}])", setOf(RegexOption.IGNORE_CASE))

        /** A sentence: up to and including its end mark, or to a line break or the end of the block. */
        private val SENTENCE = Regex("[^.!?\\n]+(?:[.!?]+|$)", RegexOption.MULTILINE)

        /** A word, or a placeholder, which renders as at least one. */
        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]*|\\x{FFFC}")

        private val DOUBLE_SPACE = Regex("(?<=\\S) {2,}(?=\\S)")

        private val SPACE_BEFORE_PUNCTUATION = Regex("(?<=[\\p{L}\\p{N}\\x{FFFC}]) +(?=[,.;:!?])")
    }
}

/** Rule sets the built-in engine ships with, until profiles supply rules (ADR 0046). */
object BuiltinRuleSets {
    /**
     * The rules of the default "clear language" profile the system catalog will ship: plain, short,
     * tidy writing for public communication. Their ids are the ones that profile will use, so a finding
     * keeps its identity, and its ignore, when the profile takes over.
     */
    val CLEAR_LANGUAGE: List<BuiltinRule> = listOf(
        BuiltinRule(
            id = "clear-language.repeated-word",
            title = "No repeated words",
            intent = "A word written twice in a row is almost always a typing mistake.",
            severity = QualitySeverity.WARNING,
            check = BuiltinCheck.RepeatedWord(allowed = mapOf("nl" to setOf("dat", "die"), "en" to setOf("had", "that"))),
        ),
        BuiltinRule(
            id = "clear-language.sentence-length",
            title = "Short sentences",
            intent = "Keep sentences to at most 25 words: long sentences are hard to follow, " +
                "for readers at language level B1 above all.",
            severity = QualitySeverity.INFO,
            check = BuiltinCheck.MaxSentenceWords(limit = 25),
        ),
        BuiltinRule(
            id = "clear-language.double-space",
            title = "One space between words",
            intent = "One space between words; more shows as an uneven gap.",
            severity = QualitySeverity.INFO,
            check = BuiltinCheck.DoubleSpace,
        ),
        BuiltinRule(
            id = "clear-language.space-before-punctuation",
            title = "No space before punctuation",
            intent = "No space before a punctuation mark, except in languages that put one there.",
            severity = QualitySeverity.INFO,
            check = BuiltinCheck.SpaceBeforePunctuation(exceptLanguages = setOf("fr")),
        ),
        BuiltinRule(
            id = "clear-language.plain-words",
            title = "Plain words",
            intent = "Prefer everyday words to formal or official ones: readers understand them at once.",
            severity = QualitySeverity.INFO,
            check = BuiltinCheck.WordList(
                entries = mapOf(
                    "nl" to linkedMapOf(
                        "aangaande" to listOf("over"),
                        "alsmede" to listOf("en", "ook"),
                        "derhalve" to listOf("daarom"),
                        "dienaangaande" to listOf("daarover"),
                        "doch" to listOf("maar"),
                        "gelieve" to listOf("wilt u"),
                        "hetgeen" to listOf("wat"),
                        "indien" to listOf("als"),
                        "inzake" to listOf("over"),
                        "middels" to listOf("met", "via"),
                        "omtrent" to listOf("over"),
                        "onverwijld" to listOf("meteen"),
                        "reeds" to listOf("al"),
                        "ten aanzien van" to listOf("over"),
                        "ten behoeve van" to listOf("voor"),
                        "teneinde" to listOf("om"),
                        "tevens" to listOf("ook"),
                        "thans" to listOf("nu"),
                        "vooralsnog" to listOf("voorlopig"),
                        "wederom" to listOf("weer", "opnieuw"),
                        "zulks" to listOf("dat"),
                    ),
                    "en" to linkedMapOf(
                        "aforementioned" to listOf("this", "these"),
                        "at this point in time" to listOf("now"),
                        "commence" to listOf("start", "begin"),
                        "endeavour" to listOf("try"),
                        "forthwith" to listOf("at once"),
                        "in accordance with" to listOf("under", "following"),
                        "in order to" to listOf("to"),
                        "in the event that" to listOf("if"),
                        "notwithstanding" to listOf("despite"),
                        "prior to" to listOf("before"),
                        "pursuant to" to listOf("under"),
                        "subsequent to" to listOf("after"),
                        "utilise" to listOf("use"),
                        "utilize" to listOf("use"),
                        "whilst" to listOf("while"),
                        "with regard to" to listOf("about"),
                    ),
                ),
            ),
        ),
    )
}
