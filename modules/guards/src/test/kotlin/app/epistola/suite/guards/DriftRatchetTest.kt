// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * A ratchet over the places where two idioms coexist. Each counter may fall, never rise.
 *
 * These are not rules a build can enforce outright: the old idiom still works, and rewriting every
 * call site at once would be a worse change than living with both. What is enforceable is the
 * direction. Left alone, the counts grow — between the review's measurement and this test being
 * written, four of them did, without anyone deciding to.
 *
 * When a counter falls, lower its baseline in the same change. The failure message carries the new
 * number, so it is a one-line edit. That is the half of the ratchet that keeps it tight; a ratchet
 * that only blocks growth drifts back up to its baseline and stays there.
 *
 * Counts are raw line and file matches — the same thing the review measured — so they are
 * comparable with `docs/agent-effectiveness-review.md` Appendix F rather than subtly different.
 */
class DriftRatchetTest {

    private data class Counter(
        val label: String,
        val baseline: Int,
        val preferred: String,
        val count: () -> Int,
    )

    private val handlerClass = Regex("""class\s+[A-Za-z0-9]*Handler\b""")

    private val counters = listOf(
        Counter("onNonHtmx {} call sites outside epistola-web", 107, "onFullPage {}") {
            mainLines(Regex("""\bonNonHtmx\b""")) { !it.startsWith("modules/epistola-web/") }
        },
        Counter("raw ServerResponse.ok().render(", 39, "page() or htmx { fragment() }") {
            mainLines(Regex("""ServerResponse\.ok\(\)\.render\("""))
        },
        Counter("val tenantId: TenantKey", 73, "val tenantKey: TenantKey") {
            mainLines(Regex("""\bval tenantId: TenantKey\b"""))
        },
        Counter("handler files using require(", 28, "validate(...) with a ValidationCode") {
            handlerFiles(Regex("""\brequire\("""))
        },
        Counter("handler files annotated @Transactional", 11, "the mediator's transaction") {
            handlerFiles(Regex("""@Transactional"""))
        },
        Counter("mediator.send/query in epistola-mcp", 26, "execute() / query() extensions") {
            mainLines(Regex("""\bmediator\.(send|query)\(""")) { it.startsWith("modules/epistola-mcp/") }
        },
        Counter("INSERT INTO in test sources", 71, "commands or the fixture DSL") {
            RepoSources.testKotlinFiles().sumOf { path ->
                Files.readString(path).lineSequence().count { "INSERT INTO" in it }
            }
        },
        Counter("UUID.randomUUID() in main", 10, "UUIDv7.generate()") {
            mainLines(Regex("""UUID\.randomUUID\(\)"""))
        },
        Counter("files importing the catalog.CatalogKey typealias", 39, "common.ids.CatalogKey") {
            RepoSources.mainKotlinFiles().count { path ->
                "import app.epistola.suite.catalog.CatalogKey" in Files.readString(path)
            }
        },
        Counter("Pagination.paginate( in rest-api", 12, "database LIMIT/OFFSET") {
            mainLines(Regex("""Pagination\.paginate\(""")) { it.startsWith("modules/rest-api/") }
        },
        Counter("handlers/*.kt declaring another package", 25, "directory matches package") {
            RepoSources.mainKotlinFiles().count { path ->
                val relative = RepoSources.relativize(path)
                // Appendix F scopes this to the host app, where the flat handlers package lives.
                if (!relative.startsWith("apps/epistola/") || "/handlers/" !in relative) return@count false
                val declared = PACKAGE.find(Files.readString(path))?.groupValues?.get(1)
                declared != null && !declared.endsWith(".handlers")
            }
        },
        Counter("Kotlin main files over 1000 lines", 1, "split the file") {
            RepoSources.mainKotlinFiles().count { Files.readAllLines(it).size > 1000 }
        },
        Counter("editor TypeScript files over 1000 lines", 5, "split the file") {
            RepoSources.uiAssetFiles().count {
                it.toString().endsWith(".ts") && Files.readAllLines(it).size > 1000
            }
        },
    )

    @Test
    fun `drift counters may fall, never rise`() {
        val risen = counters.mapNotNull { counter ->
            val now = counter.count()
            if (now > counter.baseline) {
                "${counter.label}: ${counter.baseline} -> $now. Prefer ${counter.preferred}."
            } else {
                null
            }
        }

        assertTrue(
            risen.isEmpty(),
            "These counters grew. The old idiom still compiles, but new code should use the new one " +
                "(see docs/agent-effectiveness-review.md Appendix F):\n\n" +
                risen.joinToString("\n"),
        )
    }

    @Test
    fun `a counter that falls tightens its baseline`() {
        val fallen = counters.mapNotNull { counter ->
            val now = counter.count()
            if (now < counter.baseline) "${counter.label}: lower the baseline from ${counter.baseline} to $now" else null
        }

        assertTrue(
            fallen.isEmpty(),
            "These counters improved — thank you. Lower their baselines in this change, so the " +
                "ground gained is held:\n\n" + fallen.joinToString("\n"),
        )
    }

    private fun mainLines(pattern: Regex, where: (String) -> Boolean = { true }): Int = RepoSources.mainKotlinFiles()
        .filter { where(RepoSources.relativize(it)) }
        .sumOf { path -> Files.readString(path).lineSequence().count { pattern.containsMatchIn(it) } }

    private fun handlerFiles(pattern: Regex): Int = RepoSources.mainKotlinFiles().count { path ->
        val text = Files.readString(path)
        handlerClass.containsMatchIn(text) && pattern.containsMatchIn(text)
    }

    private companion object {
        private val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
    }
}
