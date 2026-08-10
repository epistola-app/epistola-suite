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
 * Browser coverage for the defaults (locale) form — same guards as
 * [FeatureTogglesUiTest]: the hx-post form swap + OOB corner notice, and the
 * htmx inheritance trap (the boosted Cancel link must navigate, not swap its
 * page into the form — hence hx-disinherit on the form).
 */
class DefaultsUiTest : BasePlaywrightTest() {

    @Test
    fun `saving swaps the form in place and shows the corner notice`() {
        val tenant = createUiTenant()

        gotoAndReady("/tenants/${tenant.id}/defaults")
        page.locator("#locale").selectOption("nl-NL")
        page.locator("button:has-text('Save')").click()
        page.htmxSettle()

        assertThat(page.locator("#notices .notice-message")).hasText("Defaults saved.")
        // Still the defaults page; the swapped-in form shows the new effective locale.
        assertThat(page.locator("[data-testid=page-title]")).hasText("Defaults")
        assertThat(page.locator("form .form-hint")).containsText("nl-NL")
    }

    @Test
    fun `cancel navigates away instead of swapping its page into the form`() {
        val tenant = createUiTenant()

        gotoAndReady("/tenants/${tenant.id}/defaults")
        page.locator("a:has-text('Cancel')").click()

        assertThat(page).hasURL(Pattern.compile(".*/tenants/${tenant.id}$"))
        // The regression left the defaults header standing with the tenant page
        // swapped inside the form; a real navigation replaces the title.
        assertThat(page.locator("[data-testid=page-title]")).not().hasText("Defaults")
    }

    private fun createUiTenant(): Tenant = withMediator {
        CreateTenant(TenantKey.of("test-dfui-${System.nanoTime()}"), "Defaults UI Test").execute()
    }
}
