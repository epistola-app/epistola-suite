// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * The built-in language checks: simple writing rules that need no dictionary, no model and no
 * service, so some language checking is always available (ADR 0044, the pilot).
 *
 * - **Repeated words** ("de de", "the the"), except the few a language repeats on purpose.
 * - **Long sentences**, over [LONG_SENTENCE_WORDS] words: the commonest advice for plain language.
 * - **Spacing**: two spaces between words, a space before punctuation.
 * - **Discouraged words**: a short list per language of formal words with a plain alternative. It is
 *   the start of a default clear-language writing profile, not the profile itself; profiles will
 *   own such lists.
 *
 * Messages are English for now, like the rest of the quality report.
 */
@Component
class WritingRulesChecker : TextChecker {
    override val checkerId = QualitySourceId("writing-rules")

    override val displayName = "Writing rules"

    override val egress = TextEgress.IN_PROCESS

    /** The rules apply to any language; the word lists to the ones listed. Bump [RULES_VERSION] when a rule changes. */
    override fun readiness(locale: ResolvedLocale): TextCheckerReadiness = TextCheckerReadiness.Ready("$RULES_VERSION/${language(locale.tag)}")

    override fun check(
        texts: List<String>,
        locale: ResolvedLocale,
        configuration: String,
    ): List<List<TextDiagnostic>> {
        val language = language(locale.tag)
        return texts.map { text ->
            repeatedWords(text, language) + longSentences(text) + spacing(text, language) + discouragedWords(text, language)
        }
    }

    private fun repeatedWords(text: String, language: String): List<TextDiagnostic> = REPEATED_WORD.findAll(text)
        .filter { it.groupValues[1].lowercase() !in INTENDED_REPEATS[language].orEmpty() }
        .map { match ->
            val word = match.groupValues[1]
            TextDiagnostic(
                ruleId = "repeated-word",
                category = TextCategory.GRAMMAR,
                severity = QualitySeverity.WARNING,
                message = "“$word” appears twice in a row.",
                offset = match.range.first,
                length = match.value.length,
                suggestions = listOf(word),
            )
        }.toList()

    private fun longSentences(text: String): List<TextDiagnostic> = SENTENCE.findAll(text).mapNotNull { match ->
        val sentence = match.value.trimEnd()
        val words = WORD.findAll(sentence).count()
        if (words <= LONG_SENTENCE_WORDS) return@mapNotNull null
        val leading = sentence.length - sentence.trimStart().length
        TextDiagnostic(
            ruleId = "long-sentence",
            category = TextCategory.READABILITY,
            severity = QualitySeverity.INFO,
            message = "This sentence has $words words. Sentences of more than $LONG_SENTENCE_WORDS words are hard to read; consider splitting it.",
            offset = match.range.first + leading,
            length = sentence.length - leading,
        )
    }.toList()

    private fun spacing(text: String, language: String): List<TextDiagnostic> {
        val doubled = DOUBLE_SPACE.findAll(text).map {
            TextDiagnostic(
                ruleId = "double-space",
                category = TextCategory.TYPOGRAPHY,
                severity = QualitySeverity.INFO,
                message = "Two or more spaces between words.",
                offset = it.range.first,
                length = it.value.length,
                suggestions = listOf(" "),
            )
        }
        // French puts a space before ; : ! ? on purpose.
        val beforePunctuation = if (language == "fr") {
            emptySequence()
        } else {
            SPACE_BEFORE_PUNCTUATION.findAll(text).map {
                TextDiagnostic(
                    ruleId = "space-before-punctuation",
                    category = TextCategory.TYPOGRAPHY,
                    severity = QualitySeverity.INFO,
                    message = "A space before a punctuation mark.",
                    offset = it.range.first,
                    length = it.value.length,
                    suggestions = listOf(""),
                )
            }
        }
        return (doubled + beforePunctuation).toList()
    }

    private fun discouragedWords(text: String, language: String): List<TextDiagnostic> = DISCOURAGED[language].orEmpty().flatMap { entry ->
        entry.pattern.findAll(text).map { match ->
            TextDiagnostic(
                ruleId = "discouraged-word",
                category = TextCategory.STYLE,
                severity = QualitySeverity.INFO,
                message = "“${match.value}” is formal; plain language prefers ${entry.alternatives.joinToString(" or ") { "“$it”" }}.",
                offset = match.range.first,
                length = match.value.length,
                suggestions = entry.alternatives.map { matchCase(it, match.value) },
            )
        }.toList()
    }

    private class Discouraged(phrase: String, val alternatives: List<String>) {
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}])" + phrase.split(' ').joinToString("\\s+") { Regex.escape(it) } + "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
    }

    companion object {
        /** Part of every result's cache key: raise it when a rule or a list changes. */
        const val RULES_VERSION = "writing-rules/1"

        const val LONG_SENTENCE_WORDS = 25

        private fun language(tag: String): String = Locale.forLanguageTag(tag).language.ifBlank { "und" }

        private fun matchCase(replacement: String, original: String): String = if (original.firstOrNull()?.isUpperCase() == true) {
            replacement.replaceFirstChar { it.titlecase(Locale.ROOT) }
        } else {
            replacement
        }

        private val REPEATED_WORD = Regex("(?<![\\p{L}\\p{N}])(\\p{L}+)\\s+(\\1)(?![\\p{L}\\p{N}])", setOf(RegexOption.IGNORE_CASE))

        /** Words a language repeats on purpose ("dat dat", "had had"). */
        private val INTENDED_REPEATS = mapOf(
            "nl" to setOf("dat", "die"),
            "en" to setOf("had", "that"),
        )

        /** A sentence: up to and including its end mark, or to a line break or the end of the block. */
        private val SENTENCE = Regex("[^.!?\\n]+(?:[.!?]+|$)", RegexOption.MULTILINE)

        /** A word, or a placeholder, which renders as at least one. */
        private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]*|\\x{FFFC}")

        private val DOUBLE_SPACE = Regex("(?<=\\S) {2,}(?=\\S)")

        private val SPACE_BEFORE_PUNCTUATION = Regex("(?<=[\\p{L}\\p{N}\\x{FFFC}]) +(?=[,.;:!?])")

        private val DISCOURAGED: Map<String, List<Discouraged>> = mapOf(
            "nl" to listOf(
                Discouraged("aangaande", listOf("over")),
                Discouraged("alsmede", listOf("en", "ook")),
                Discouraged("derhalve", listOf("daarom")),
                Discouraged("dienaangaande", listOf("daarover")),
                Discouraged("doch", listOf("maar")),
                Discouraged("gelieve", listOf("wilt u")),
                Discouraged("hetgeen", listOf("wat")),
                Discouraged("indien", listOf("als")),
                Discouraged("inzake", listOf("over")),
                Discouraged("middels", listOf("met", "via")),
                Discouraged("omtrent", listOf("over")),
                Discouraged("onverwijld", listOf("meteen")),
                Discouraged("reeds", listOf("al")),
                Discouraged("ten aanzien van", listOf("over")),
                Discouraged("ten behoeve van", listOf("voor")),
                Discouraged("teneinde", listOf("om")),
                Discouraged("tevens", listOf("ook")),
                Discouraged("thans", listOf("nu")),
                Discouraged("vooralsnog", listOf("voorlopig")),
                Discouraged("wederom", listOf("weer", "opnieuw")),
                Discouraged("zulks", listOf("dat")),
            ),
            "en" to listOf(
                Discouraged("aforementioned", listOf("this", "these")),
                Discouraged("at this point in time", listOf("now")),
                Discouraged("commence", listOf("start", "begin")),
                Discouraged("endeavour", listOf("try")),
                Discouraged("forthwith", listOf("at once")),
                Discouraged("in accordance with", listOf("under", "following")),
                Discouraged("in order to", listOf("to")),
                Discouraged("in the event that", listOf("if")),
                Discouraged("notwithstanding", listOf("despite")),
                Discouraged("prior to", listOf("before")),
                Discouraged("pursuant to", listOf("under")),
                Discouraged("subsequent to", listOf("after")),
                Discouraged("utilise", listOf("use")),
                Discouraged("utilize", listOf("use")),
                Discouraged("whilst", listOf("while")),
                Discouraged("with regard to", listOf("about")),
            ),
        )
    }
}
