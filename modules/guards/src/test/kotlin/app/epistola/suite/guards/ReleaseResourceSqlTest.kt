// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Only the storage code names `release_resources` in SQL (ADR 0033). The table holds more than a
 * release's entries: a template's variants are rows of their own (storage type `variant`), which are
 * no wire resource. A reader that lists, counts or fingerprints entries straight from the table would
 * count them too; it reads `release_wire_resources` (wire resources only), a type's view
 * (`current_templates`, `current_template_variants`, …) or `catalog_version_resources(type)`.
 *
 * Code migrations are history, and the test-infrastructure conversions check what a migration wrote,
 * so they are left out; tests may inspect the table.
 */
class ReleaseResourceSqlTest {

    @Test
    fun `only the storage code names release_resources in SQL`() {
        val sources = RepoSources.mainKotlinFiles()
        assertTrue(sources.size > 300, "Found only ${sources.size} sources — the scan is broken")
        val violations = sources
            .filterNot { path -> RepoSources.relativize(path).let { relative -> ALLOWED.any { it in relative } } }
            .flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line ->
                    STATEMENT.find(line)?.let { "${RepoSources.relativize(path)}:${index + 1}: ${it.value}" }
                }
            }
        assertTrue(
            violations.isEmpty(),
            "These statements name release_resources outside the storage code. Read release_wire_resources (wire resources " +
                "only), a type's view or catalog_version_resources(type), and write through DraftResources or VariantRows:\n" +
                violations.joinToString("\n"),
        )
    }

    private companion object {
        val STATEMENT = Regex("""\b(FROM|JOIN|INTO|UPDATE)\s+release_resources\b""", RegexOption.IGNORE_CASE)

        /** The storage code: the writers of draft, variant and release rows, and the content store and collector. */
        val ALLOWED = listOf(
            "/catalog/revisions/DraftResources.kt",
            "/catalog/revisions/VariantRows.kt",
            "/catalog/revisions/ReleaseResourceStore.kt",
            "/catalog/revisions/ResourceContentStore.kt",
            "/catalog/revisions/ResourceContentCollector.kt",
            // The generic reader of a version (ADR 0039): it reads a type's part rows by the type's own part type.
            "/catalog/revisions/VersionResourceReader.kt",
            "/src/main/kotlin/db/migration/",
            "modules/testing/",
        )
    }
}
