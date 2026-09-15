// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertTrue

/**
 * Ratchet that prevents the Playwright flakiness anti-patterns (issue #418)
 * from creeping back into UI tests. Plain unit test — no Spring, no Docker —
 * so it runs in the fast `unitTest` cycle and gates every PR.
 *
 * Required patterns (see `docs/testing.md`): navigate via
 * `BasePlaywrightTest.gotoAndReady`, await HTMX via `htmxSettle()`, open
 * dialogs via `openDialogByTrigger`, assert web-first. The helper layer
 * (`PlaywrightHtmxSupport`) and the base class legitimately contain the raw
 * primitives, so they are exempt.
 *
 * UI tests are found by what they extend, not by where they live: `@Tag("ui")` sits on the
 * abstract base and is inherited, so scanning for the annotation finds one file, and scanning a
 * single package missed eight browser tests that had grown up beside the features they cover.
 */
class UiTestHygieneTest {

    private data class BannedPattern(
        val regex: Regex,
        val why: String,
    )

    private val banned = listOf(
        BannedPattern(
            Regex("""\.waitForTimeout\("""),
            "waitForTimeout races a wall clock on slow CI — assert the end state web-first instead",
        ),
        BannedPattern(
            Regex(""":visible"""),
            "the Playwright :visible pseudo is evaluated at query time — select shown elements structurally",
        ),
        BannedPattern(
            Regex("""waitForSelector\([^)]*\[open]"""),
            "blind waitForSelector(\"…[open]\") is the #418 dialog race — use openDialogByTrigger",
        ),
        BannedPattern(
            Regex("""\bpage\.navigate\("""),
            "bare page.navigate skips the readiness wait — use gotoAndReady",
        ),
        BannedPattern(
            Regex("""System\.err\.println"""),
            "forensic System.err dumps mask flakiness — rely on the persisted Playwright trace",
        ),
    )

    /** Helpers/base legitimately hold the raw primitives the rules wrap; this guard names them. */
    private val exempt = setOf("PlaywrightHtmxSupport.kt", "BasePlaywrightTest.kt", "UiTestHygieneTest.kt")

    private val extendsBase = ": BasePlaywrightTest("

    @Test
    fun `UI tests must use the deterministic helpers, not the flaky primitives`() {
        val uiTests = RepoSources.testKotlinFiles()
            .filter { it.name !in exempt }
            .filter { extendsBase in Files.readString(it) }

        assertTrue(
            uiTests.size >= 20,
            "Found only ${uiTests.size} browser tests — the detector is broken, not the suite clean",
        )

        val violations = mutableListOf<String>()

        uiTests.forEach { path: Path ->
            Files.readAllLines(path).forEachIndexed { idx, line ->
                // Ignore comment lines so rule rationale in KDoc/// is allowed.
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                banned.forEach { (regex, why) ->
                    if (regex.containsMatchIn(line)) {
                        violations.add("${RepoSources.relativize(path)}:${idx + 1} — $why\n    > ${line.trim()}")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "UI test hygiene violations (issue #418 — see docs/testing.md):\n\n" +
                violations.joinToString("\n\n"),
        )
    }
}
