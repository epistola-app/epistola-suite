// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.mediator.execute
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Making something new from the catalog page (docs/catalog-workspace-navigation.md): each kind's
 * "New" opens its create dialog in place, on this catalog, and closing it leaves you where you were.
 */
class CatalogContentsUiTest : BasePlaywrightTest() {

    @Test
    fun `New template opens its dialog on the catalog page, on this catalog, and closing it keeps the catalog page's address`() {
        val tenant = createTenant("Catalog Contents New")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("letters"), "Letters").execute() }
        val catalogPage = "/tenants/${tenant.id}/catalogs/letters/browse"

        gotoAndReady(catalogPage)
        page.locator("[data-testid='catalog-status-new-template']").click()
        page.htmxSettle()

        val dialog = page.locator("dialog[open]#create-template-dialog")
        assertThat(dialog).isVisible()
        // Inside a catalog there is no catalog to choose: it creates here.
        assertThat(dialog.locator("form#create-template-form")).hasAttribute("hx-post", java.util.regex.Pattern.compile(".*/catalogs/letters/templates$"))
        assertThat(dialog.locator("select#catalog")).hasCount(0)

        page.keyboard().press("Escape")
        assertThat(page.locator("dialog[open]#create-template-dialog")).hasCount(0)
        assertThat(page).hasURL(Pattern.compile(".*/catalogs/letters/browse$"))
    }
}
