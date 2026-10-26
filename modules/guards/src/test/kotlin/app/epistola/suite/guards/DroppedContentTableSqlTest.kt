// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * No SQL names a content table that moved to JSON storage (ADR 0033): its rows are draft rows of
 * `release_resources` now, written through `DraftResources` and read through the type's view
 * (`current_themes`). The table is dropped, so a statement on it fails at run time; this fails it at
 * build time, with where to go instead.
 *
 * Code migrations and the migration tests that run them at an older schema are history, and the
 * 1.x upgrade checks read the 1.x schema, so they are left out.
 */
class DroppedContentTableSqlTest {

    @Test
    fun `no statement names a table moved to JSON storage`() {
        val sources = RepoSources.mainKotlinFiles() + RepoSources.testKotlinFiles()
        assertTrue(sources.size > 500, "Found only ${sources.size} sources — the scan is broken")
        val violations = sources
            .filterNot { path -> RepoSources.relativize(path).let { relative -> EXEMPT.any { it in relative } } }
            .flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line ->
                    DROPPED.find(line)?.let { "${RepoSources.relativize(path)}:${index + 1}: ${it.value}" }
                }
            }
        assertTrue(
            violations.isEmpty(),
            "These statements name a table that moved to JSON storage (ADR 0033). Read the type's view " +
                "(current_themes, current_code_lists, current_attributes, current_stencils, current_fonts, current_images, current_templates, current_template_variants) and write through its store (DraftResources):\n" + violations.joinToString("\n"),
        )
    }

    private companion object {
        val DROPPED = Regex("""\b(FROM|JOIN|INTO|UPDATE|TABLE)\s+(themes|code_lists|code_list_entries|variant_attribute_definitions|stencils|fonts|font_variants|assets|content_store|document_templates|template_variants|catalog_releases|catalog_release_dependencies)\b""", RegexOption.IGNORE_CASE)

        val EXEMPT = listOf(
            "/src/main/kotlin/db/migration/",
            "/src/test/kotlin/app/epistola/suite/migration/",
            "/migration/preflight/",
            // Not SQL: a constraint-violation message the test truncates.
            "ApiExceptionHandlerTruncationTest.kt",
            "DroppedContentTableSqlTest.kt",
        )
    }
}
