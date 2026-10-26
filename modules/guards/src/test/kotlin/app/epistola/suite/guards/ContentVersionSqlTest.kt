// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Every SQL statement that reads or writes catalog content names the catalog version it means
 * (contract ADR 0002, several drafts per catalog).
 *
 * Content rows belong to a catalog version (`catalog_versions`): a draft of an authored catalog, or
 * the installed release of a subscribed one. Once a catalog can hold several drafts, a statement that
 * filters content by catalog alone returns one row per draft, or writes into whichever the database
 * picks. So a statement touching a content table must say which version: by `version_id` (the
 * caller's own, or a parent row's), through `versionAt(...)` or `inCurrentVersion(...)`
 * in `CatalogSql.kt`,
 * or, for a tenant-wide read, by joining `catalog_current_versions`.
 *
 * The scan looks at each string literal in main Kotlin sources. A literal that reads from, joins,
 * inserts into or updates a content table, and mentions none of those, fails — unless its file is in
 * [allowed] with the reason it legitimately does not. A statement assembled from several literals is
 * checked per literal, so the part naming the table must also name the version.
 */
class ContentVersionSqlTest {

    @Test
    fun `every statement on catalog content names its catalog version`() {
        val sources = RepoSources.mainKotlinFiles()
        assertTrue(
            sources.size > 100,
            "Found only ${sources.size} main sources — the scan is broken, not the repository clean",
        )

        val violations = mutableListOf<String>()
        var scanned = 0
        for (path in sources) {
            val relative = RepoSources.relativize(path)
            if (allowedPrefixes.keys.any { relative.startsWith(it) } || relative in allowed) continue
            val source = Files.readString(path)
            for ((offset, literal) in stringLiterals(source)) {
                val touched = TOUCHES.findAll(literal).map { it.value.replace(WHITESPACE, " ") }.toList()
                if (touched.isEmpty()) continue
                scanned++
                if (NAMES_VERSION.containsMatchIn(literal)) continue
                val line = source.substring(0, offset).count { it == '\n' } + 1
                violations.add("$relative:$line: ${touched.distinct().joinToString(", ")}")
            }
        }

        // Fewer as each type moved to JSON storage and its reads to a view (ADR 0033): 21 after step 7b.
        assertTrue(scanned > 10, "Found only $scanned statements on content — the scan is broken")
        assertTrue(
            violations.isEmpty(),
            "These statements touch catalog content without naming the catalog version. Filter or join " +
                "on version_id, use versionAt(...) or inCurrentVersion(...) from CatalogSql.kt, or join " +
                "catalog_current_versions for a tenant-wide read. Allow a file in ContentVersionSqlTest " +
                "only with the reason it needs no version:\n" + violations.joinToString("\n"),
        )
    }

    /**
     * Files whose statements on content need no version, each with its reason. Keep this short: a new
     * entry is a statement that cannot pick up a second draft's rows, not one that was hard to convert.
     */
    private val allowed: Map<String, String> = mapOf(
        // Reads a 1.x database before the upgrade migrates it: content there has no catalog versions yet.
        "modules/epistola-core/src/main/kotlin/app/epistola/suite/migration/preflight/OneXCatalogReferences.kt" to
            "reads the 1.x schema, before catalog versions exist",
        // The tenant purge deletes the attribute definitions of every version of every catalog.
        "modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/PurgeTenantCatalogs.kt" to
            "deletes all of a tenant's content",
        // A namespace rename rewrites the dependency declarations of every version that names it.
        "modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/ChangeCatalogNamespace.kt" to
            "renames a dependency in every version",
        // Bytes found by content hash: any row holding the hash has the same bytes, and the release
        // being assembled may hold a face no current version still has.
        "modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/revisions/ReleaseContentAssembler.kt" to
            "font face bytes looked up by content hash",
        "modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/revisions/ResourceContentStore.kt" to
            "asset storage scope looked up by content hash",
    )

    /** Directories allowed as a whole, with their reason. */
    private val allowedPrefixes: Map<String, String> = mapOf(
        // Java-based Flyway migrations run against the schema of their own time, before content
        // carried a version (V20261018201000); they are history and are never edited.
        "modules/epistola-core/src/main/kotlin/db/migration/" to "historical Flyway migrations",
    )

    /**
     * The string literals of a Kotlin source and their offsets, with comments skipped. Triple-quoted
     * and ordinary strings both count; a `${...}` template is kept as part of its literal, so a helper
     * such as `${versionAt("t", "c")}` is seen in the statement it builds.
     */
    private fun stringLiterals(source: String): List<Pair<Int, String>> {
        val literals = mutableListOf<Pair<Int, String>>()
        var i = 0
        val n = source.length
        while (i < n) {
            when {
                source.startsWith("//", i) -> i = source.indexOf('\n', i).let { if (it < 0) n else it }

                source.startsWith("/*", i) -> i = source.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }

                source[i] == '\'' -> i = skipCharLiteral(source, i)

                source.startsWith("\"\"\"", i) -> {
                    val end = rawStringEnd(source, i + 3)
                    literals.add(i to source.substring(i + 3, end))
                    i = end + 3
                }

                source[i] == '"' -> {
                    val end = stringEnd(source, i + 1)
                    literals.add(i to source.substring(i + 1, end))
                    i = end + 1
                }

                else -> i++
            }
        }
        return literals
    }

    private fun skipCharLiteral(source: String, start: Int): Int {
        var j = start + 1
        j += if (j < source.length && source[j] == '\\') 2 else 1
        return if (j < source.length && source[j] == '\'') j + 1 else start + 1
    }

    private fun rawStringEnd(source: String, from: Int): Int {
        var j = from
        var depth = 0
        while (j < source.length) {
            when {
                source.startsWith("\${", j) -> {
                    depth++
                    j += 2
                    continue
                }

                depth > 0 && source[j] == '}' -> depth--

                depth == 0 && source.startsWith("\"\"\"", j) -> {
                    // A raw string may end in quotes: the last three close it.
                    while (j + 3 < source.length && source[j + 3] == '"') j++
                    return j
                }
            }
            j++
        }
        return source.length
    }

    private fun stringEnd(source: String, from: Int): Int {
        var j = from
        var depth = 0
        while (j < source.length) {
            val c = source[j]
            when {
                c == '\\' -> {
                    j += 2
                    continue
                }

                source.startsWith("\${", j) -> {
                    depth++
                    j += 2
                    continue
                }

                depth > 0 && c == '{' -> depth++

                depth > 0 && c == '}' -> depth--

                depth > 0 && c == '"' -> {
                    // A string inside the template expression, e.g. versionAt("t", "c").
                    var k = j + 1
                    while (k < source.length && source[k] != '"') k += if (source[k] == '\\') 2 else 1
                    j = k + 1
                    continue
                }

                depth == 0 && (c == '"' || c == '\n') -> return j
            }
            j++
        }
        return source.length
    }

    private companion object {
        private val CONTENT_TABLES = listOf(
            // Every resource type is draft rows of release_resources now (ADR 0033), read through its view,
            // which holds only the version a caller means; DroppedContentTableSqlTest refuses the old table.
            "resource_ready_marks",
            "catalog_version_dependencies",
            "resource_content_digests",
        )
        private val TOUCHES = Regex(
            """\b(FROM|JOIN|INTO|UPDATE)\s+(${CONTENT_TABLES.joinToString("|")})\b""",
            RegexOption.IGNORE_CASE,
        )
        private val NAMES_VERSION = Regex("""version_id|versionAt\(|inCurrentVersion\(|catalog_current_versions""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
