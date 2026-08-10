// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Browser coverage for the feature-toggles form, which is browser-only logic on
 * both sides: the hx-post form swap + OOB corner notice, and — the regression
 * this guards — htmx attribute inheritance. The form's `hx-target="this"` /
 * `hx-swap="outerHTML"` are inherited by the boosted Cancel link inside it;
 * without `hx-disinherit` the link's page swaps INTO the form instead of
 * navigating (caught live 2026-08-09).
 */
class FeatureTogglesUiTest : BasePlaywrightTest() {

    @Test
    fun `saving swaps the form in place and shows the corner notice`() {
        val tenant = createUiTenant()

        gotoAndReady("/tenants/${tenant.id}/features")
        page.locator("button:has-text('Save Settings')").click()
        page.htmxSettle()

        assertThat(page.locator("#notices .notice-message")).hasText("Feature toggle settings saved.")
        // Still the features page, form intact after the outerHTML swap.
        assertThat(page.locator("[data-testid=page-title]")).hasText("Feature Toggles")
        assertThat(page.locator("button:has-text('Save Settings')")).isVisible()
    }

    @Test
    fun `cancel navigates away instead of swapping its page into the form`() {
        val tenant = createUiTenant()

        gotoAndReady("/tenants/${tenant.id}/features")
        page.locator("a:has-text('Cancel')").click()

        assertThat(page).hasURL(Pattern.compile(".*/tenants/${tenant.id}$"))
        // The regression left the features header standing with the tenant page
        // swapped inside the form; a real navigation replaces the title.
        assertThat(page.locator("[data-testid=page-title]")).not().hasText("Feature Toggles")
    }

    private fun createUiTenant(): Tenant = withMediator {
        CreateTenant(TenantKey.of("test-ftui-${System.nanoTime()}"), "Feature Toggles UI Test").execute()
    }
}
