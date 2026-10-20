// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.mediator.execute
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Moving around inside a catalog replaces only the page (docs/workspace-shell.md): the context bar
 * stays as it is — the same element, its status shown throughout — and the side nav follows.
 */
class WorkspaceNavigationUiTest : BasePlaywrightTest() {

    @Test
    fun `a nav click inside a catalog keeps the bar and replaces the page and the nav`() {
        val tenant = createTenant("Workspace Navigation")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("letters"), "Letters").execute() }

        gotoAndReady("/tenants/${tenant.id}/catalogs/letters/browse")
        assertThat(page.locator("#ws-status [data-testid='ws-pending'], #ws-status [data-testid='catalog-draft-none']").first()).isVisible()
        // Mark what is on screen now, and record whether the status is ever empty while moving.
        page.evaluate(
            """() => {
                window.__bar = document.getElementById('app-nav');
                window.__page = document.getElementById('ws-page');
                window.__statusEmptied = false;
                document.addEventListener('htmx:afterSwap', () => {
                    const status = document.getElementById('ws-status');
                    if (!status || !status.innerHTML.trim()) window.__statusEmptied = true;
                });
            }""",
        )

        page.locator("[data-testid='ws-nav-stencils']").click()
        assertThat(page).hasURL(Pattern.compile(".*/catalogs/letters/stencils$"))
        page.htmxSettle()

        Assertions.assertThat(page.evaluate("() => document.getElementById('app-nav') === window.__bar") as Boolean)
            .`as`("the bar is the same element").isTrue()
        Assertions.assertThat(page.evaluate("() => document.getElementById('ws-page') !== window.__page") as Boolean)
            .`as`("the page is new").isTrue()
        Assertions.assertThat(page.evaluate("() => window.__statusEmptied") as Boolean)
            .`as`("the bar's status never empties").isFalse()
        assertThat(page.locator("[data-testid='ws-nav-stencils']")).hasClass(Pattern.compile(".*\\bactive\\b.*"))
        assertThat(page.locator("h1, #page-title-text").first()).containsText("Stencils")
    }
}
