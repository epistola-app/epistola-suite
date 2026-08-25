// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

class ExchangeRedirectNavigationTest {
    @Test
    fun `exchange authorization forms bypass htmx for cross-origin redirects`() {
        val template = RepoSources.repoRoot
            .resolve("apps/epistola/src/main/resources/templates/exchange.html")
            .readText()
        val authorizationForms = Regex(
            """<form\b[^>]*exchange/connect[^>]*>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(template).map { it.value }.toList()

        assertThat(authorizationForms).hasSize(2)
        assertThat(authorizationForms).allMatch { "hx-boost=\"false\"" in it }
    }
}
