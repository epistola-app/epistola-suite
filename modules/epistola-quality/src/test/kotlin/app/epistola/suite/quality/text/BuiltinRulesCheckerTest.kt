// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.i18n.LocaleSource
import app.epistola.suite.i18n.ResolvedLocale
import app.epistola.suite.quality.CheckTrigger
import app.epistola.suite.quality.QualitySeverity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BuiltinRulesCheckerTest {
    private val checker = BuiltinRulesChecker()

    private fun check(text: String, tag: String = "en-GB", engine: BuiltinRulesChecker = checker): List<TextDiagnostic> {
        val locale = ResolvedLocale(tag, LocaleSource.VARIANT)
        val configuration = (engine.readiness(locale) as TextCheckerReadiness.Ready).configuration
        return engine.check(listOf(text), locale, configuration).single()
    }

    private fun List<TextDiagnostic>.flagged(text: String) = map { it.ruleId to text.substring(it.offset, it.offset + it.length) }

    @Test
    fun `in-process, on every trigger, on for every tenant, keyed by engine version, rules and language`() {
        assertThat(checker.egress).isEqualTo(TextEgress.IN_PROCESS)
        assertThat(checker.triggers).containsExactlyInAnyOrderElementsOf(CheckTrigger.entries)
        assertThat(checker.switchable).isFalse()
        val configuration = (checker.readiness(ResolvedLocale("nl-NL", LocaleSource.INSTALLATION)) as TextCheckerReadiness.Ready).configuration
        assertThat(configuration).startsWith(BuiltinRulesChecker.ENGINE_VERSION).endsWith("/nl")
    }

    @Test
    fun `other rules are another cache key`() {
        val locale = ResolvedLocale("en-GB", LocaleSource.VARIANT)
        val stricter = BuiltinRulesChecker(listOf(rule(BuiltinCheck.MaxSentenceWords(limit = 10))))

        assertThat(stricter.readiness(locale)).isNotEqualTo(checker.readiness(locale))
    }

    @Test
    fun `a finding names its rule, takes the rule's severity, and explains itself with the rule's intent`() {
        val text = "Please send the the form."
        val diagnostic = check(text).single()

        assertThat(diagnostic.ruleId).isEqualTo("clear-language.repeated-word")
        assertThat(text.substring(diagnostic.offset, diagnostic.offset + diagnostic.length)).isEqualTo("the the")
        assertThat(diagnostic.severity).isEqualTo(QualitySeverity.WARNING)
        assertThat(diagnostic.suggestions).containsExactly("the")
        assertThat(diagnostic.explanation).isEqualTo(BuiltinRuleSets.CLEAR_LANGUAGE.first { it.id == diagnostic.ruleId }.intent)
    }

    @Test
    fun `repeats a language makes on purpose are not flagged, and only whole words count`() {
        assertThat(check("Ik weet dat dat klopt.", "nl-NL")).isEmpty()
        assertThat(check("She had had enough.")).isEmpty()
        assertThat(check("The theme is there.")).isEmpty()
    }

    @Test
    fun `the sentence limit is the rule's parameter, and a placeholder counts as one word`() {
        val engine = BuiltinRulesChecker(listOf(rule(BuiltinCheck.MaxSentenceWords(limit = 5))))
        val long = "One two three four ￼ six."
        val text = "Short one. $long Short again."

        val diagnostic = check(text, engine = engine).single()

        assertThat(text.substring(diagnostic.offset, diagnostic.offset + diagnostic.length)).isEqualTo(long)
        assertThat(diagnostic.category).isEqualTo(TextCategory.READABILITY)
        assertThat(diagnostic.message).contains("6 words").contains("limit is 5")
        assertThat(check("One two three four five.", engine = engine)).isEmpty()
    }

    @Test
    fun `the default profile's sentence limit is 25 words`() {
        val words = { n: Int -> (1..n).joinToString(" ") { "word$it" } + "." }

        assertThat(check(words(25))).isEmpty()
        assertThat(check(words(26)).map { it.ruleId }).containsExactly("clear-language.sentence-length")
    }

    @Test
    fun `doubled spaces and a space before punctuation are flagged, but not in French`() {
        val text = "Dear  customer , welcome!"
        assertThat(check(text).flagged(text))
            .containsExactlyInAnyOrder("clear-language.double-space" to "  ", "clear-language.space-before-punctuation" to " ")
        assertThat(check("Bonjour ; merci !", "fr-FR")).isEmpty()
    }

    @Test
    fun `a word list flags its phrases per language with their alternatives, matching the capital`() {
        val text = "Middels deze brief, ten behoeve van u."
        val diagnostics = check(text, "nl-NL")

        assertThat(diagnostics.flagged(text)).containsExactly("clear-language.plain-words" to "Middels", "clear-language.plain-words" to "ten behoeve van")
        assertThat(diagnostics[0].suggestions).containsExactly("Met", "Via")
        assertThat(diagnostics[1].suggestions).containsExactly("voor")
        assertThat(check("Middels deze brief.", "en-GB")).isEmpty()
        assertThat(check("We use utilities.", "en-GB")).isEmpty()
    }

    @Test
    fun `each text gets its own answer, in order`() {
        val locale = ResolvedLocale("en-GB", LocaleSource.TENANT)
        val answers = checker.check(listOf("Fine.", "the the end", "Also fine."), locale, "any")

        assertThat(answers.map { it.size }).containsExactly(0, 1, 0)
    }

    private fun rule(check: BuiltinCheck) = BuiltinRule("test.rule", "Test", "Because.", QualitySeverity.INFO, check)
}
