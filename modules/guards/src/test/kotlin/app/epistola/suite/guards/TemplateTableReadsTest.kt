// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Templates are read through `current_templates` and `current_template_variants` (ADR 0033, step 7a),
 * which answer a draft's rows or a targeted release's content, so a read works on either. Until
 * templates move to JSON storage (step 7b), only what writes the template tables names them: the
 * template commands, the import, a move, and the few draft-wide reads listed below, which step 7b
 * rewrites with the writes.
 */
class TemplateTableReadsTest {

    @Test
    fun `only template writes name the template tables`() {
        val sources = RepoSources.mainKotlinFiles()
        assertTrue(sources.size > 300, "Found only ${sources.size} sources — the scan is broken")
        val violations = sources
            .filterNot { path -> RepoSources.relativize(path).let { relative -> WRITERS.any { relative.endsWith(it) } || EXEMPT.any { it in relative } } }
            .flatMap { path ->
                Files.readAllLines(path).mapIndexedNotNull { index, line ->
                    TABLES.find(line)?.let { "${RepoSources.relativize(path)}:${index + 1}: ${it.value}" }
                }
            }
        assertTrue(
            violations.isEmpty(),
            "These statements read the template tables (ADR 0033, step 7a). Read current_templates and " +
                "current_template_variants, which answer a release too:\n" + violations.joinToString("\n"),
        )
    }

    private companion object {
        val TABLES = Regex("""\b(FROM|JOIN|INTO|UPDATE|TABLE)\s+(document_templates|template_variants)\b""", RegexOption.IGNORE_CASE)

        val WRITERS = listOf(
            "templates/commands/CreateDocumentTemplate.kt",
            "templates/commands/DeleteDataExample.kt",
            "templates/commands/DeleteDocumentTemplate.kt",
            "templates/commands/MarkTemplateReady.kt",
            "templates/commands/UpdateDataExample.kt",
            "templates/commands/UpdateDocumentTemplate.kt",
            "templates/commands/variants/CreateVariant.kt",
            "templates/commands/variants/DeleteVariant.kt",
            "templates/commands/variants/DistinctAttributeSet.kt",
            "templates/commands/variants/MarkVariantReady.kt",
            "templates/commands/variants/SetDefaultVariant.kt",
            "templates/commands/variants/UpdateVariant.kt",
            "templates/commands/variants/UpdateVariantContent.kt",
            "templates/contracts/commands/UpdateDataContract.kt",
            // The row a write locks (readDataContract with forUpdate).
            "templates/contracts/queries/GetDataContract.kt",
            "templates/TemplateSql.kt",
            "catalog/commands/ImportTemplates.kt",
            "catalog/relocation/CatalogResourceMovePlanner.kt",
            "catalog/relocation/CatalogResourceRelocation.kt",
            // Builds a release from the draft's rows, and from 1.x's published ones.
            "catalog/CatalogContentBuilder.kt",
            // Across every draft of a catalog, not the one a caller means.
            "catalog/releases/CatalogDraftCommands.kt",
            "generation/release/ReleaseTargetResolver.kt",
            // Not SQL: an example in a comment.
            "config/JdbiJsonbSupport.kt",
        )

        val EXEMPT = listOf(
            "/src/main/kotlin/db/migration/",
            "/migration/preflight/",
        )
    }
}
