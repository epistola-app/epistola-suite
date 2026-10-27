// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The freeze (ADR 0036, step (f)): a released version's rows do not change, and the application
 * enforces it. Every SQL write to what a version holds (its content rows, ready marks, code list
 * refreshes, declarations, draft merges and its settings on `catalog_versions`) sits either in a
 * writer of draft content that refuses a released version first (`requireWritableVersion`,
 * `Handle.writable`, or the `inDraft` predicate), or in the release path, which writes a release on
 * purpose: freezing, recording, installing, deleting and restoring one.
 *
 * A new writer is one of the two: guarded, or added to [RELEASE_PATH] with its reason.
 */
class FrozenVersionWriteGuardTest {

    @Test
    fun `every write to a version's rows refuses a released version or is the release path`() {
        val sources = RepoSources.mainKotlinFiles()
        assertTrue(sources.size > 300, "Found only ${sources.size} sources — the scan is broken")
        val violations = sources
            .filterNot { path -> RepoSources.relativize(path).let { relative -> RELEASE_PATH.keys.any { it in relative } } }
            .mapNotNull { path ->
                val text = Files.readString(path)
                val writes = WRITE.findAll(text).map { it.value }.toList()
                if (writes.isEmpty() || GUARD.containsMatchIn(text)) null else "${RepoSources.relativize(path)}: ${writes.distinct().joinToString()}"
            }
        assertTrue(
            violations.isEmpty(),
            "These files write a version's rows without refusing a released version. Call requireWritableVersion " +
                "(or Handle.writable) first, or add the inDraft predicate; or, for the release path, list the file in " +
                "RELEASE_PATH with why:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `the release path lists only files that still write a version's rows`() {
        val stale = RELEASE_PATH.keys.filter { fragment ->
            RepoSources.mainKotlinFiles().none { path -> fragment in RepoSources.relativize(path) && WRITE.containsMatchIn(Files.readString(path)) }
        }
        assertTrue(stale.isEmpty(), "These RELEASE_PATH entries no longer write a version's rows; remove them: $stale")
    }

    private companion object {
        val WRITE = Regex(
            """\b(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+(?:release_resources|resource_ready_marks|code_list_refreshes|catalog_version_dependencies|catalog_draft_merges)\b""" +
                """|\bUPDATE\s+catalog_versions\b[^"]*?\bSET\s+(?:default_theme_resource_id|presets)\b""",
            RegexOption.IGNORE_CASE,
        )

        val GUARD = Regex("""requireWritableVersion\(|\.writable\(|inDraft\(""")

        /** Writers of a release on purpose, by path fragment, each with why. */
        val RELEASE_PATH = mapOf(
            "/catalog/releases/CatalogReleases.kt" to "the version lifecycle: opening, freezing, starting the next version, retiring, deleting a draft",
            "/catalog/revisions/ReleaseResourceStore.kt" to "records a release's rows and deletes them with the release",
            "/catalog/commands/RecordInstalledRelease.kt" to "installs a received release",
            "/catalog/commands/DeleteCatalog.kt" to "deletes a catalog with every version",
            "/catalog/commands/ChangeCatalogNamespace.kt" to "renames what every version names",
            "/catalog/commands/ReceiveOneXSubscribedReleases.kt" to "renames, as ChangeCatalogNamespace does, what the drafts name of a 1.x catalog taking its source's name",
            "/catalog/commands/CatalogImportRows.kt" to "receives a release into the catalog",
            "/catalog/dependencies/CatalogDependencyStore.kt" to "also records a release's pins (recordRelease, pinDeclarationsOf); its draft writer replace() is guarded",
            "/catalog/revisions/VariantRows.kt" to "also records a release's variant rows (recordRelease); its draft writers are guarded",
            "/src/main/kotlin/db/migration/" to "code migrations are history",
        )
    }
}
