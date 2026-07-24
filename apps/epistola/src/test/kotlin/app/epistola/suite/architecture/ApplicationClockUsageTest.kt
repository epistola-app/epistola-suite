// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Enforces the application-time rule from docs/clock.md: application code must read time
 * through EpistolaClock (which resolves the Clock bound in MediatorContext), never via
 * direct JVM now() calls. Direct now() calls bypass the test clock and break deterministic
 * time control in tests and schedulers.
 *
 * The with-clock overloads (e.g. OffsetDateTime.now(clock)) remain allowed — EpistolaClock
 * itself uses them. Database NOW() in SQL is also fine (database-owned timestamps).
 */
class ApplicationClockUsageTest {

    private val bannedNowCall =
        Regex("""\b(Instant|OffsetDateTime|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|YearMonth)\.now\(\s*\)""")

    /** Files allowed to call JVM now() directly; each entry must document its reason. */
    private val allowedFiles = setOf<String>()

    @Test
    fun `application code must use EpistolaClock instead of JVM now()`() {
        val violations = mutableListOf<String>()

        for (path in RepoSources.mainKotlinFiles()) {
            val relative = RepoSources.relativize(path)
            if (relative in allowedFiles) continue
            // Shared test infrastructure is not application code; it manages clocks itself.
            if (relative.startsWith("modules/testing/")) continue

            val code = RepoSources.stripComments(Files.readString(path))
            code.lineSequence().forEachIndexed { index, line ->
                if (bannedNowCall.containsMatchIn(line)) {
                    violations.add("$relative:${index + 1}: ${line.trim()}")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Direct JVM now() calls found — use EpistolaClock (see docs/clock.md), " +
                "or add the file to allowedFiles with a documented reason:\n${violations.joinToString("\n")}",
        )
    }
}
