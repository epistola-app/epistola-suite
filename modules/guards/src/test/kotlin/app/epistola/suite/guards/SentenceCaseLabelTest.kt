// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Headings, buttons, table headers, labels and dialog titles are in sentence case: "New template",
 * not "New Template". The UI had both side by side ("Create New Tenant" above "Getting started").
 * A capital after the first word is allowed for a proper noun or an acronym ([PROPER]) and for a
 * value the template fills in.
 */
class SentenceCaseLabelTest {

    @Test
    fun `labels are in sentence case`() {
        val labelText = listOf(
            Regex("""<(h[1-4]|button|th|label)\b[^>]*>([^<]*)<"""),
            Regex("""\b(pageTitle|title|dialogTitle)='([^'$]+)'"""),
        )
        val violations = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
            val html = RepoSources.stripHtmlComments(Files.readString(path))
            labelText.flatMap { pattern -> pattern.findAll(html).map { it.groupValues[2].trim() } }
                .filter { titleCased(it) }
                .map { "${RepoSources.relativize(path)}: \"$it\"" }
        }.distinct()
        assertTrue(violations.isEmpty(), "Labels in Title Case; write them in sentence case:\n" + violations.joinToString("\n"))
    }

    /** A capitalised word after the first that is not a proper noun or acronym. */
    private fun titleCased(text: String): Boolean {
        // A quoted name ("Refresh from source") is another control's label, cited as it reads; a leading
        // symbol ("+ New variant") is not the first word.
        val words = text.replace(Regex(""""[^"]*""""), "").trimStart('+', ' ').split(Regex("""\s+""")).map { it.trim(',', '.', ':', '…', '(', ')', '"', '\'') }.filter { it.isNotEmpty() }
        return words.drop(1).any { it.first().isUpperCase() && it.drop(1).all { c -> c.isLowerCase() } && it !in PROPER }
    }

    private companion object {
        val PROPER = setOf(
            "Epistola", "Exchange", "Suite", "Keycloak", "Inter", "Settings", "Always", "Never", "Production",
            "Default", "System", "Letters", "Shared", "Redesign", "Test", "Enter", "Escape",
        )
    }
}
