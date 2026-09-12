// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Enforces the separation between UI handlers and the REST API: UI code — Thymeleaf templates,
 * static JavaScript, the editor's TypeScript — must never call anything under `/api/`. The REST
 * API is for external systems and is stable and versioned; a UI need gets a UI route under
 * `/tenants/`.
 *
 * This guard was previously unable to fail. It matched `['"/](api/)?v1/`, but the controllers are
 * `@RequestMapping("/api")` with no version segment, so no real endpoint could match it — and it
 * resolved its own relative paths, so it saw only the host app while eight other modules ship
 * templates and two ship static JavaScript.
 */
class UiRestApiSeparationTest {

    /** `/api/` in a string literal or an attribute value: a fetch, an `hx-*` target or an import. */
    private val restCall = Regex("""["'(=]/api/""")

    private val restMediaType = "application/vnd.epistola.v1+json"

    @Test
    fun `UI code must not call the REST API`() {
        val assets = RepoSources.uiAssetFiles()
        assertTrue(
            assets.size > 100,
            "Found only ${assets.size} UI assets — the scan is broken, not the repository clean",
        )

        val violations = mutableListOf<String>()

        for (path in assets) {
            val relative = RepoSources.relativize(path)
            val raw = Files.readString(path)
            // A comment mentioning the REST API is discussion, not a call.
            val code = if (path.toString().endsWith(".html")) {
                RepoSources.stripHtmlComments(raw)
            } else {
                RepoSources.stripComments(raw)
            }

            code.lineSequence().forEachIndexed { index, line ->
                if (restCall.containsMatchIn(line)) {
                    violations.add("$relative:${index + 1} calls the REST API: ${line.trim()}")
                }
                if (restMediaType in line) {
                    violations.add("$relative:${index + 1} uses the REST media type: ${line.trim()}")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "UI code must not call /api/** — add a UI route under /tenants/** instead " +
                "(see apps/epistola/AGENTS.md):\n${violations.joinToString("\n")}",
        )
    }
}
