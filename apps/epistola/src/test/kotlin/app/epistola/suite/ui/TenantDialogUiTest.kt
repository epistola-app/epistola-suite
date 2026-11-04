// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The platform's tenants page lists first and creates in a dialog, as every other list does. A
 * tenant needs a name and an ID only: its administrator connects authorities when its catalogs need
 * a name of their own (ADR 0034, amendment of 2026-11-03).
 */
class TenantDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `New tenant opens a dialog that creates the tenant from a name and an ID, closes, and shows it in the list`() {
        val key = "dialog-${System.nanoTime().toString().takeLast(8)}"

        gotoAndReady("/")
        page.openDialogByTrigger(page.locator("[data-testid='tenant-create-open']"), "#create-tenant-dialog")

        page.locator("#create-tenant-dialog #name").fill("Dialog Tenant $key")
        page.locator("#create-tenant-dialog #slug").fill(key)
        page.locator("#create-tenant-dialog [data-testid='create-form-submit']").click()
        page.htmxSettle()

        assertThat(page.locator("dialog[open]#create-tenant-dialog")).hasCount(0)
        assertThat(page.locator("#tenant-rows")).containsText("Dialog Tenant $key")
    }
}
