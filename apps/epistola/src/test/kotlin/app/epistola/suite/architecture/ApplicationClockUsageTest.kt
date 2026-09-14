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
 *
 * Two more ways to read the wall clock are covered. `Clock.systemUTC()` is allowed only as the
 * `: Clock = Clock.systemUTC()` seam — the default a caller overrides, which is what EpistolaClock,
 * the mediator context and the deliberately core-free renderer in `modules/generation` all use.
 * `System.currentTimeMillis()` is allowed only where it measures an elapsed duration rather than
 * naming a moment; those files are listed with their reason.
 */
class ApplicationClockUsageTest {

    private data class Rule(
        val regex: Regex,
        val why: String,
        /** Lines matching this are the legitimate form of the construct. */
        val exceptLines: Regex? = null,
        /** Files allowed to use it at all; each entry documents its reason below. */
        val allowedFiles: Set<String> = emptySet(),
    )

    private val rules = listOf(
        Rule(
            regex = Regex("""\b(Instant|OffsetDateTime|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|YearMonth)\.now\(\s*\)"""),
            why = "reads the JVM clock directly — use EpistolaClock",
        ),
        Rule(
            regex = Regex("""Clock\.systemUTC\(\)"""),
            why = "binds the system clock — application time comes from EpistolaClock",
            // `: Clock = Clock.systemUTC()` is a default a caller overrides: the EpistolaClock seam
            // itself, the mediator context, and the explicit clock the pure renderer accepts.
            exceptLines = Regex(""":\s*Clock\s*=\s*Clock\.systemUTC\(\)"""),
        ),
        Rule(
            regex = Regex("""System\.currentTimeMillis\(\)"""),
            why = "reads the JVM clock directly — use EpistolaClock, or measure elapsed time",
            allowedFiles = setOf(
                // Elapsed age of the last poll, reported to the health indicator. A duration
                // between two readings of the same clock, not a moment in application time.
                "modules/epistola-core/src/main/kotlin/app/epistola/suite/documents/batch/JobPoller.kt",
                // Wall-clock duration of a load-test run and its timeout arithmetic.
                "modules/loadtest/src/main/kotlin/app/epistola/suite/loadtest/batch/LoadTestExecutor.kt",
                // The browser cookie must expire with the servlet container's session, so it is
                // derived from the container's own maxInactiveInterval and clock, not ours.
                "apps/epistola/src/main/kotlin/app/epistola/suite/config/SessionExpiryCookieFilter.kt",
            ),
        ),
    )

    @Test
    fun `application code must use EpistolaClock instead of the JVM clock`() {
        val sources = RepoSources.mainKotlinFiles()
        assertTrue(
            sources.size > 100,
            "Found only ${sources.size} main sources — the scan is broken, not the repository clean",
        )

        val violations = mutableListOf<String>()

        for (path in sources) {
            val relative = RepoSources.relativize(path)
            // Shared test infrastructure is not application code; it manages clocks itself.
            if (relative.startsWith("modules/testing/")) continue

            val code = RepoSources.stripComments(Files.readString(path))
            code.lineSequence().forEachIndexed { index, line ->
                for (rule in rules) {
                    if (relative in rule.allowedFiles) continue
                    if (!rule.regex.containsMatchIn(line)) continue
                    if (rule.exceptLines?.containsMatchIn(line) == true) continue
                    violations.add("$relative:${index + 1}: ${rule.why}\n    > ${line.trim()}")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Application time must come from EpistolaClock (see docs/clock.md). Add a file to the " +
                "rule's allowedFiles with a documented reason only when it measures elapsed time or " +
                "belongs to another clock's domain:\n${violations.joinToString("\n")}",
        )
    }
}
