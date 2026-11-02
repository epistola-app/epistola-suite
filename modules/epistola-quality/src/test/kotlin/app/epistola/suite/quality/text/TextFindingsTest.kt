// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.QualitySubjectType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The fingerprint is the contract the ledger's auto-resolve and ignores rest on (`docs/quality.md`):
 * the same problem keeps it across runs, another problem gets another.
 */
class TextFindingsTest {
    private val checker = QualitySourceId("languagetool")
    private val subject = QualitySubject(
        type = QualitySubjectType.VARIANT,
        urn = "urn:epistola:variant:acme/x/default/letter/main",
        ignoreScopeUrn = "urn:epistola:template:acme/x/default/letter",
        tenantKey = TenantKey.of("acme"),
        catalogKey = CatalogKey.of("default"),
        templateKey = TemplateKey.of("letter"),
        variantKey = "main",
    )

    private fun block(nodeId: String, vararg parts: TextPart) = TextBlock(nodeId, parts.toList())

    private fun spelling(offset: Int, length: Int) = TextDiagnostic("SPELL", TextCategory.SPELLING, QualitySeverity.WARNING, "Possible typo", offset, length, listOf("text"))

    private fun findings(blocks: List<TextBlock>, diagnostics: Map<String, List<TextDiagnostic>>) = TextFindings.of(checker, subject, blocks, diagnostics, "cfg")

    @Test
    fun `a finding is on the block's node, quotes the flagged text as the author wrote it, and carries the suggestions`() {
        val b = block("n1", TextPart.Literal("Dear "), TextPart.Placeholder("name"), TextPart.Literal(", your tekst is here."))

        val finding = findings(listOf(b), mapOf(b.hash to listOf(spelling(13, 5)))).single()

        assertThat(finding.nodeIds).containsExactly("n1")
        assertThat(finding.context.path("quote").asString()).isEqualTo("tekst")
        assertThat(finding.context.path("before").asString()).isEqualTo("Dear {name}, your ")
        assertThat(finding.context.path("suggestions").toList().map { it.asString() }).containsExactly("text")
        assertThat(finding.metadata.path("configuration").asString()).isEqualTo("cfg")
    }

    @Test
    fun `a diagnostic touching a placeholder is dropped`() {
        val b = block("n1", TextPart.Literal("Dear "), TextPart.Placeholder("name"), TextPart.Literal(","))

        assertThat(findings(listOf(b), mapOf(b.hash to listOf(spelling(5, 1), spelling(4, 2), spelling(0, 99))))).isEmpty()
    }

    @Test
    fun `the same typo keeps its fingerprint across runs and edits elsewhere, another typo gets another`() {
        val before = block("n1", TextPart.Literal("A tekst here."))
        val edited = block("n1", TextPart.Literal("Now, a tekst here."))
        val other = block("n1", TextPart.Literal("A txet here."))

        val first = findings(listOf(before), mapOf(before.hash to listOf(spelling(2, 5)))).single()
        val afterEdit = findings(listOf(edited), mapOf(edited.hash to listOf(spelling(7, 5)))).single()
        val another = findings(listOf(other), mapOf(other.hash to listOf(spelling(2, 4)))).single()

        assertThat(afterEdit.fingerprint).isEqualTo(first.fingerprint)
        assertThat(another.fingerprint).isNotEqualTo(first.fingerprint)
    }

    @Test
    fun `rewording a sentence that is still too long is the same readability problem`() {
        val long = { text: String -> block("n1", TextPart.Literal(text)) }
        val readability = { length: Int -> TextDiagnostic("long-sentence", TextCategory.READABILITY, QualitySeverity.INFO, "Long", 0, length) }
        val a = long("One long sentence.")
        val b = long("One long sentence, reworded.")

        assertThat(findings(listOf(a), mapOf(a.hash to listOf(readability(18)))).single().fingerprint)
            .isEqualTo(findings(listOf(b), mapOf(b.hash to listOf(readability(28)))).single().fingerprint)
    }

    @Test
    fun `the same typo twice in a block, or in two blocks sharing a hash, gives distinct fingerprints`() {
        val b1 = block("n1", TextPart.Literal("tekst and tekst"))
        val b2 = block("n2", TextPart.Literal("tekst and tekst"))
        val diagnostics = mapOf(b1.hash to listOf(spelling(0, 5), spelling(10, 5)))

        val all = findings(listOf(b1, b2), diagnostics)

        assertThat(all).hasSize(4)
        assertThat(all.map { it.fingerprint }.toSet()).hasSize(4)
        assertThat(all.map { it.nodeIds.single() }).containsExactly("n1", "n1", "n2", "n2")
    }
}
