// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the Backups page's "Back up now" flow: the hx-post form
 * in the page-header actions swaps the #backup-list region elsewhere on the
 * page (an out-of-form target, unlike the self-swapping settings forms) and
 * the corner notice rides OOB — first run "Backup completed.", unchanged
 * re-run the dedup message.
 */
class BackupsUiTest : BasePlaywrightTest() {

    @Test
    fun `back up now swaps the list in place and shows the corner notice`() {
        val tenant = createTenant("Backups UI")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.SUPPORT_BACKUPS, enabled = true).execute()
        }

        gotoAndReady("/tenants/${tenant.id.value}/backups")
        page.htmxSettle()

        page.locator("button:has-text('Back up now')").click()
        page.htmxSettle()

        assertThat(page.locator("#notices .notice-message")).hasText("Backup completed.")
        // The swapped-in region shows the fresh backup instead of the empty state.
        assertThat(page.locator("#backup-list .ep-badge:has-text('Latest')")).isVisible()

        // Unchanged re-run: the dedup message replaces the first notice on top.
        page.locator("button:has-text('Back up now')").click()
        page.htmxSettle()
        assertThat(page.locator("#notices .notice-message").first())
            .hasText("No changes since the last backup.")
    }
}
