// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the catalog-mutation corner notices: the success notice
 * arrives as an OOB swap on the SAME response that closes the modal dialog
 * (HX-Trigger closeDialog + HX-Reswap none), so this pins the whole chain —
 * OOB processed despite the none-swap, notice surviving the dialog-close
 * region restore, and the mount (timer) applying. Create stands in for all
 * four mutation flows; the mechanics are shared.
 */
class CatalogNoticeUiTest : BasePlaywrightTest() {

    @Test
    fun `creating a catalog closes the dialog and shows the corner notice`() {
        val tenant = createTenant("Catalog Notice UI")

        gotoAndReady("/tenants/${tenant.id}/catalogs")
        page.htmxSettle()

        val dialog = page.openDialogByTrigger(
            page.locator("[data-testid='catalog-create-open']"),
            "#create-catalog-dialog",
        )
        dialog.locator("input[name='slug']").fill("notice-ui")
        dialog.locator("input[name='name']").fill("Notice UI Catalog")
        dialog.locator("button[type='submit']").click()
        page.htmxSettle()

        assertThat(page.locator("dialog[open]#create-catalog-dialog")).hasCount(0)
        assertThat(page.locator("#notices .notice-message")).hasText("Catalog created.")
        assertThat(page.locator("#catalog-list")).containsText("Notice UI Catalog")
    }
}
