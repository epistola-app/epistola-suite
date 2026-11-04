// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Settings, Authorities & namespaces in a browser: the tenant's administrator finds it in Settings,
 * adds a namespace under the organisation's domain in a dialog, and removes it again.
 */
class TenantAuthoritiesUiTest : BasePlaywrightTest() {

    @Test
    fun `an administrator adds a domain's namespace from Settings, and removes it`() {
        val tenant = TenantKey.of("ui-authorities-${System.nanoTime().toString().takeLast(8)}")
        withMediator { CreateTenant(tenant, "Authorities UI").execute() }

        gotoAndReady("/tenants/${tenant.value}")
        page.locator("[data-testid='nav-item-authorities']").click()
        page.htmxSettle()
        assertThat(page.getByTestId("page-title")).containsText("Authorities & namespaces")
        assertThat(page.getByTestId("domains-empty")).isVisible()

        page.openDialogByTrigger(page.getByTestId("namespace-grant-open"), "#grant-namespace-dialog")
        page.getByTestId("namespace-grant-authority").fill("gemeente-x.nl")
        page.getByTestId("namespace-grant-namespace").fill("acme")
        page.getByTestId("namespace-grant-submit").click()
        page.htmxSettle()

        assertThat(page.locator("dialog[open]#grant-namespace-dialog")).hasCount(0)
        val domain = page.locator("[data-testid='authority-domain'][data-authority='gemeente-x.nl']")
        assertThat(domain.locator("[data-namespace='gemeente-x.nl/acme']")).isVisible()

        domain.getByTestId("namespace-revoke").click()
        page.getByTestId("confirm-dialog-confirm").click()
        page.htmxSettle()
        assertThat(page.locator("[data-testid='authority-domain']")).hasCount(0)
        assertThat(page.getByTestId("domains-empty")).isVisible()
    }
}
