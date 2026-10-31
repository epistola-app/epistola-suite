// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.LocaleSource
import app.epistola.suite.i18n.ResolvedLocale
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WritingRulesCheckerTest {
    private val checker = WritingRulesChecker()

    private fun check(text: String, tag: String = "en-GB"): List<TextDiagnostic> {
        val locale = ResolvedLocale(tag, LocaleSource.VARIANT)
        val configuration = (checker.readiness(locale) as TextCheckerReadiness.Ready).configuration
        return checker.check(listOf(text), locale, configuration).single()
    }

    private fun List<TextDiagnostic>.flagged(text: String) = map { it.ruleId to text.substring(it.offset, it.offset + it.length) }

    @Test
    fun `always ready, keyed by its rule version and the language`() {
        assertThat(checker.readiness(ResolvedLocale("nl-NL", LocaleSource.INSTALLATION)))
            .isEqualTo(TextCheckerReadiness.Ready("${WritingRulesChecker.RULES_VERSION}/nl"))
        assertThat(checker.egress).isEqualTo(TextEgress.IN_PROCESS)
    }

    @Test
    fun `a repeated word is flagged with the single word as its fix`() {
        val text = "Please send the the form."
        val diagnostics = check(text)

        assertThat(diagnostics.flagged(text)).containsExactly("repeated-word" to "the the")
        assertThat(diagnostics.single().suggestions).containsExactly("the")
    }

    @Test
    fun `repeats a language makes on purpose are not flagged, and only whole words count`() {
        assertThat(check("Ik weet dat dat klopt.", "nl-NL")).isEmpty()
        assertThat(check("She had had enough.")).isEmpty()
        assertThat(check("The theme is there.")).isEmpty()
    }

    @Test
    fun `a sentence over the limit is flagged as a whole, its words counted, placeholders as one`() {
        val long = (1..WritingRulesChecker.LONG_SENTENCE_WORDS).joinToString(" ") { "word$it" } + " ￼."
        val text = "Short one. $long Short again."
        val diagnostic = check(text).single()

        assertThat(diagnostic.ruleId).isEqualTo("long-sentence")
        assertThat(diagnostic.category).isEqualTo(TextCategory.READABILITY)
        assertThat(text.substring(diagnostic.offset, diagnostic.offset + diagnostic.length)).isEqualTo(long)
        assertThat(diagnostic.message).contains("${WritingRulesChecker.LONG_SENTENCE_WORDS + 1} words")
    }

    @Test
    fun `a sentence at the limit is not flagged`() {
        assertThat(check((1..WritingRulesChecker.LONG_SENTENCE_WORDS).joinToString(" ") { "word$it" } + ".")).isEmpty()
    }

    @Test
    fun `doubled spaces and a space before punctuation are flagged, but not in French`() {
        val text = "Dear  customer , welcome!"
        assertThat(check(text).flagged(text)).containsExactlyInAnyOrder("double-space" to "  ", "space-before-punctuation" to " ")
        assertThat(check("Bonjour ; merci !", "fr-FR")).isEmpty()
    }

    @Test
    fun `a discouraged word or phrase is flagged with plain alternatives, matching its capital`() {
        val text = "Middels deze brief, ten behoeve van u."
        val diagnostics = check(text, "nl-NL")

        assertThat(diagnostics.flagged(text)).containsExactly("discouraged-word" to "Middels", "discouraged-word" to "ten behoeve van")
        assertThat(diagnostics[0].suggestions).containsExactly("Met", "Via")
        assertThat(diagnostics[1].suggestions).containsExactly("voor")
    }

    @Test
    fun `word lists are per language and match whole words only`() {
        assertThat(check("Middels deze brief.", "en-GB")).isEmpty()
        assertThat(check("We use utilities.", "en-GB")).isEmpty()
        assertThat(check("Prior to the meeting.", "en-GB").map { it.suggestions }).containsExactly(listOf("Before"))
    }

    @Test
    fun `each text gets its own answer, in order`() {
        val locale = ResolvedLocale("en-GB", LocaleSource.TENANT)
        val answers = checker.check(listOf("Fine.", "the the end", "Also fine."), locale, "any")

        assertThat(answers.map { it.size }).containsExactly(0, 1, 0)
    }
}
