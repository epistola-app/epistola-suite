// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.banner.commands.ClearSiteBanner
import app.epistola.suite.mediator.execute
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the site-banner admin form on `/platform/banner` — a
 * standalone page (no layout/shell), so this also proves the corner-notice
 * region the page hosts itself works outside the shell: the hx-post save swaps
 * the form in place and the OOB success notice lands and renders.
 */
class SiteBannerAdminUiTest : BasePlaywrightTest() {

    @Test
    fun `saving swaps the form in place and shows the corner notice`() {
        try {
            gotoAndReady("/platform/banner")
            page.locator("#message").fill("Scheduled maintenance tonight")
            page.locator("button[value='save']").click()
            page.htmxSettle()

            assertThat(page.locator("#notices .notice-message")).hasText("Site banner saved.")
            // The swapped-in form reflects the saved state: Clear button appeared.
            assertThat(page.locator("button[value='clear']")).isVisible()
            assertThat(page.locator("#message")).hasValue("Scheduled maintenance tonight")
        } finally {
            withMediator { ClearSiteBanner().execute() }
        }
    }
}
