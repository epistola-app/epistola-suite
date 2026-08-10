// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.mediator.execute
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Browser coverage for the browse-page install flow: the confirm submit's
 * success response closes the preview dialog (close-on-success sees the 200),
 * refreshes the resource rows, and shows the corner success notice — the OOB
 * chain the handler test can only assert as response text.
 */
class CatalogInstallUiTest : BasePlaywrightTest() {

    @Test
    fun `installing from the preview dialog closes it and shows the corner notice`() {
        val tenant = createTenant("Catalog Install UI")
        withMediator {
            RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = "classpath:epistola/catalogs/demo/catalog.json",
                authType = AuthType.NONE,
            ).execute()
        }

        gotoAndReady("/tenants/${tenant.id.value}/catalogs/epistola-demo/browse")
        page.htmxSettle()

        val dialog = page.openDialogByTrigger(
            page.locator("button:has-text('Install All')"),
            "#install-preview-dialog",
        )
        dialog.locator("button[type='submit']").click()
        page.htmxSettle()

        assertThat(page.locator("dialog[open]#install-preview-dialog")).hasCount(0)
        assertThat(page.locator("#notices .notice-message")).containsText("installed")
        assertThat(page.locator("#resource-table")).containsText("Installed")
    }
}
