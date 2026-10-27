// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code migrations are self-contained and frozen (ADR 0035).
 *
 * - **Self-contained**: a Kotlin migration (`src/main/kotlin/db/migration/`) uses JDBC, a JSON tree and
 *   frozen code only, never the suite's own classes, whose meaning moves on with every release. Its
 *   imports come from [ALLOWED_IMPORTS], or name frozen code in [FROZEN] with the reason it is frozen,
 *   and it names no `app.epistola.suite` class at all, qualified or not.
 * - **Frozen**: Flyway gives a code migration no checksum of its own, so from
 *   [FIRST_PINNED_VERSION] on each declares one, `CHECKSUM`, pinned to its source: the CRC32 of the
 *   file with that number written as `0`. Flyway records it, so an installation that ran the
 *   migration refuses to start on an edited one; `checkMigrationVersions` refuses the edit itself.
 * - **The frozen toolkit** (`db/migration/<module>/frozen/`), the logic the 2.0 upgrade's code
 *   migrations share, pins every file the same way, and a migration that uses it reports its own
 *   checksum combined with the toolkit's (`FrozenToolkit.pinned(CHECKSUM)`), so an installation refuses
 *   an edit of the toolkit too.
 */
class CodeMigrationGuardTest {

    private val sources = RepoSources.mainKotlinFiles().filter { "/src/main/kotlin/db/migration/" in RepoSources.relativize(it) }
    private val toolkit = sources.filter { "/frozen/" in RepoSources.relativize(it) }
    private val migrations = sources - toolkit.toSet()

    @Test
    fun `a code migration imports only JDBC, Jackson, Flyway, logging and frozen code`() {
        assertTrue(migrations.size >= 4, "Found only ${migrations.size} code migrations — the scan is broken")
        val violations = sources.flatMap { path ->
            val source = Files.readString(path)
            val imports = IMPORT.findAll(source).map { it.groupValues[1] }
                .filterNot { name -> ALLOWED_IMPORTS.any { name.startsWith(it) } || name in FROZEN }
                .map { "${RepoSources.relativize(path)}: import $it" }
            val suite = if ("app.epistola.suite." in source) listOf("${RepoSources.relativize(path)}: names an app.epistola.suite class") else emptyList()
            imports + suite
        }
        assertTrue(
            violations.isEmpty(),
            "A code migration may use only JDBC, a JSON tree and frozen code (ADR 0035). Read what it needs " +
                "from the tables, or make the logic a versioned algorithm with golden vectors and add it to FROZEN:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `a code migration declares a checksum pinned to its source`() {
        val pinned = migrations.filter { version(it.name) >= FIRST_PINNED_VERSION }
        assertTrue(pinned.isNotEmpty(), "No code migration from V$FIRST_PINNED_VERSION on — the scan is broken")
        for (path in pinned) {
            val source = Files.readString(path)
            val declared = CHECKSUM.find(source)?.groupValues?.get(1)?.toLong()
            val reported = "override fun getChecksum(): Int = CHECKSUM" in source || "override fun getChecksum(): Int = FrozenToolkit.pinned(CHECKSUM)" in source
            assertTrue(declared != null && reported, "${RepoSources.relativize(path)} declares no pinned CHECKSUM")
            assertEquals(pinnedChecksum(source).toLong(), declared, "${RepoSources.relativize(path)}: CHECKSUM does not match its source")
            if ("frozen." in source) {
                assertTrue("FrozenToolkit.pinned(CHECKSUM)" in source, "${RepoSources.relativize(path)} uses the frozen toolkit; its checksum must include it (FrozenToolkit.pinned)")
            }
        }
    }

    @Test
    fun `every file of the frozen toolkit declares a checksum pinned to its source`() {
        assertTrue(toolkit.size >= 3, "Found only ${toolkit.size} frozen toolkit files — the scan is broken")
        for (path in toolkit) {
            val source = Files.readString(path)
            val declared = CHECKSUM.find(source)?.groupValues?.get(1)?.toLong()
            assertTrue(declared != null, "${RepoSources.relativize(path)} declares no pinned CHECKSUM")
            assertEquals(pinnedChecksum(source).toLong(), declared, "${RepoSources.relativize(path)}: CHECKSUM does not match its source; the toolkit is frozen (ADR 0035)")
            val parts = Files.readString(toolkit.first { it.name == "FrozenToolkit.kt" })
            val name = path.name.removeSuffix(".kt")
            assertTrue(name == "FrozenToolkit" || "$name.CHECKSUM" in parts, "FrozenToolkit.pinned does not include $name")
        }
    }

    private fun version(name: String): Long = name.removePrefix("V").substringBefore("__").toLongOrNull() ?: 0

    private companion object {
        const val FIRST_PINNED_VERSION = 20261024114440L

        val IMPORT = Regex("""(?m)^import\s+([\w.]+)""")
        val CHECKSUM = Regex("""const val CHECKSUM: Int = (-?\d+)""")

        val ALLOWED_IMPORTS = listOf("java.", "javax.", "kotlin.", "org.flywaydb.", "org.slf4j.", "tools.jackson.", "db.migration.core.frozen.")

        /** Frozen code a migration may call, each with why it is frozen. */
        val FROZEN = mapOf(
            // The contract's versioned document migrations: each version's step never changes meaning.
            "app.epistola.catalog.migration.CatalogDocumentMigration" to "versioned contract migrations",
            // The contract's key grammar of a wire version, as that version defines it.
            "app.epistola.catalog.protocol.CatalogKeys" to "contract key grammar",
        ).keys

        /** The CRC32 of [source] with its pinned number written as 0. */
        fun pinnedChecksum(source: String): Int {
            val crc = CRC32()
            crc.update(CHECKSUM.replace(source, "const val CHECKSUM: Int = 0").toByteArray(Charsets.UTF_8))
            return crc.value.toInt()
        }
    }
}
