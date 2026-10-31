// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/** The platform's tenants page lists first and creates in a dialog, as every other list does. */
class TenantDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `New tenant opens a dialog that creates the tenant, closes, and shows it in the list`() {
        val key = "dialog-${System.nanoTime().toString().takeLast(8)}"

        gotoAndReady("/")
        page.openDialogByTrigger(page.locator("[data-testid='tenant-create-open']"), "#create-tenant-dialog")

        page.locator("#create-tenant-dialog #name").fill("Dialog Tenant $key")
        page.locator("#create-tenant-dialog #slug").fill(key)
        page.locator("#create-tenant-dialog [data-testid='tenant-namespace']").fill(key)
        page.locator("#create-tenant-dialog [data-testid='create-form-submit']").click()
        page.htmxSettle()

        assertThat(page.locator("dialog[open]#create-tenant-dialog")).hasCount(0)
        assertThat(page.locator("#tenant-rows")).containsText("Dialog Tenant $key")
    }
}
