// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The platform's tenants page lists first and creates in a dialog, as every other list does. A
 * tenant needs a name and an ID; naming its catalogs is optional, since it matters only once they
 * leave the installation.
 */
class TenantDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `New tenant opens a dialog that creates the tenant from a name and an ID, closes, and shows it in the list`() {
        val key = "dialog-${System.nanoTime().toString().takeLast(8)}"

        gotoAndReady("/")
        page.openDialogByTrigger(page.locator("[data-testid='tenant-create-open']"), "#create-tenant-dialog")

        page.locator("#create-tenant-dialog #name").fill("Dialog Tenant $key")
        page.locator("#create-tenant-dialog #slug").fill(key)
        // Naming its catalogs is a closed, optional section.
        assertThat(page.locator("#create-tenant-dialog [data-testid='tenant-namespace']")).isHidden()
        page.locator("#create-tenant-dialog [data-testid='create-form-submit']").click()
        page.htmxSettle()

        assertThat(page.locator("dialog[open]#create-tenant-dialog")).hasCount(0)
        assertThat(page.locator("#tenant-rows")).containsText("Dialog Tenant $key")
    }

    @Test
    fun `the namespace follows the tenant ID, and the dialog shows what its catalogs will be called`() {
        val key = "naming-${System.nanoTime().toString().takeLast(8)}"

        gotoAndReady("/")
        page.openDialogByTrigger(page.locator("[data-testid='tenant-create-open']"), "#create-tenant-dialog")
        val dialog = page.locator("#create-tenant-dialog")

        dialog.locator("[data-testid='tenant-naming'] summary").click()
        dialog.locator("#slug").fill(key)
        assertThat(dialog.locator("[data-testid='tenant-namespace']")).hasValue(key)
        assertThat(dialog.locator("[data-testid='tenant-authority']")).hasValue("")
        assertThat(dialog.locator("[data-testid='tenant-naming-preview']"))
            .hasText("Its catalogs are named $key/letters, once you name the domain that grants it.")

        dialog.locator("[data-testid='tenant-authority']").fill("gemeente-x.nl")
        assertThat(dialog.locator("[data-testid='tenant-naming-preview']"))
            .hasText("Its catalogs are named $key/letters, granted by gemeente-x.nl.")

        // Typed by hand, it stops following the ID.
        dialog.locator("[data-testid='tenant-namespace']").fill("acme-$key")
        dialog.locator("#slug").fill("$key-x")
        assertThat(dialog.locator("[data-testid='tenant-namespace']")).hasValue("acme-$key")

        dialog.locator("#name").fill("Naming Tenant $key")
        dialog.locator("[data-testid='create-form-submit']").click()
        page.htmxSettle()
        assertThat(page.locator("dialog[open]#create-tenant-dialog")).hasCount(0)
        assertThat(page.locator("#tenant-rows")).containsText("Naming Tenant $key")
    }
}
