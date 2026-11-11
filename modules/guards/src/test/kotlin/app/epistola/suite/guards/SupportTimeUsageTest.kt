// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The support modules' own budgets read elapsed time from `SupportTime`, never `System.nanoTime()`
 * directly, and do not `Thread.sleep`: a test then lets a budget run out by advancing a fake, and a
 * slow machine cannot run one out in a test that is not about it. `ApplicationClockUsageTest` is the
 * counterpart for the wall clock.
 *
 * A file that reads real time on purpose is listed with its reason.
 */
class SupportTimeUsageTest {
    private val pattern = Regex("""System\.nanoTime\(\)|Thread\.sleep\(""")

    private val support = "modules/epistola-support/src/main/kotlin/app/epistola/suite/support"

    private val allowedFiles = setOf(
        // The source itself: SupportTime.SYSTEM reads the JVM's monotonic clock.
        "$support/time/SupportTime.kt",
        // Waits for this node's background threads to finish (tests use it). It waits on real work,
        // so it needs real time: a fake that stood still would never let it give up, and one that
        // moved on its own would let it give up before the work was done.
        "$support/connection/SupportBackgroundWork.kt",
        // Caches of what is stored (the rows' snapshot, the configured connection's id) and the
        // throttle on re-storing a node's clock error: ages no test drives. They move to SupportTime
        // when a test needs to control them.
        "$support/connection/DefaultSupportConnection.kt",
        "$support/connection/SupportConnectionParts.kt",
        "$support/connection/SupportConnections.kt",
        "$support/connection/SupportNodeClock.kt",
        // Waits for another node to let go of the round lock: real work on another JVM, real time.
        "modules/epistola-support-requests/src/main/kotlin/app/epistola/suite/support/requests/sync/SupportRequestRoundLock.kt",
    )

    @Test
    fun `support production code reads elapsed time through SupportTime`() {
        val sources = RepoSources.mainKotlinFiles()
            .filter { RepoSources.relativize(it).startsWith("modules/epistola-support") }
        assertTrue(sources.size > 20, "Found only ${sources.size} support sources — the scan is broken, not the code clean")

        val violations = sources.flatMap { path ->
            val relative = RepoSources.relativize(path)
            if (relative in allowedFiles) return@flatMap emptyList()
            RepoSources.stripComments(Files.readString(path)).lineSequence()
                .mapIndexedNotNull { index, line -> if (pattern.containsMatchIn(line)) "$relative:${index + 1}\n    > ${line.trim()}" else null }
                .toList()
        }

        assertTrue(
            violations.isEmpty(),
            "Support budgets read SupportTime, not System.nanoTime() or Thread.sleep (see docs/clock.md). " +
                "List a file in allowedFiles only with the reason it needs real time:\n${violations.joinToString("\n")}",
        )
    }
}
