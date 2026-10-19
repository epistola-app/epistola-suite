// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Logging out may end at the identity provider (OIDC RP-initiated logout), on another origin. A
 * boosted form posts through HTMX, whose request cannot follow that redirect, so the logout seems
 * to do nothing. Every form posting to `/logout` opts out with `hx-boost="false"`.
 */
class LogoutNotBoostedTest {

    @Test
    fun `every logout form is a plain post`() {
        val violations = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
            Regex("""<form\b[^>]*@\{/logout}[^>]*>""").findAll(RepoSources.stripHtmlComments(Files.readString(path)))
                .filterNot { "hx-boost=\"false\"" in it.value }
                .map { "${RepoSources.relativize(path)}: ${it.value}" }
                .toList()
        }
        assertTrue(violations.isEmpty(), "Logout forms without hx-boost=\"false\":\n" + violations.joinToString("\n"))
    }
}
