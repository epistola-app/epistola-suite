// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.mediator.execute
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat as assertThatValue

class FixedTableLayoutUiTest : BasePlaywrightTest() {

    @Test
    fun `fixed list table contains horizontal overflow and keeps actions usable on a narrow viewport`() {
        val tenant = createTenant("Fixed Table Layout")
        withMediator {
            CreateApiKey(
                tenantId = tenant.id,
                name = "Responsive integration key",
            ).execute()
        }
        page.setViewportSize(640, 720)

        gotoAndReady("/tenants/${tenant.id}/api-keys")
        page.htmxSettle()

        val scroller = page.locator("[data-testid='fixed-table-scroll']")
        assertThat(scroller).isVisible()
        assertThat(scroller).hasAttribute("tabindex", "0")

        val viewportWidth = (page.evaluate("() => document.documentElement.clientWidth") as Number).toDouble()
        val pageWidth = (page.evaluate("() => document.documentElement.scrollWidth") as Number).toDouble()
        assertThatValue(pageWidth).isLessThanOrEqualTo(viewportWidth)

        val scrollerWidth = (scroller.evaluate("element => element.clientWidth") as Number).toDouble()
        val tableWidth = (scroller.evaluate("element => element.scrollWidth") as Number).toDouble()
        assertThatValue(tableWidth).isGreaterThan(scrollerWidth)

        val actionCell = page.locator("td.actions")
        val deleteButton = page.locator("[data-testid='api-key-delete']")
        assertThat(deleteButton).isVisible()
        deleteButton.scrollIntoViewIfNeeded()

        val actionBox = requireNotNull(actionCell.boundingBox())
        val buttonBox = requireNotNull(deleteButton.boundingBox())
        assertThatValue(buttonBox.x).isGreaterThanOrEqualTo(actionBox.x)
        assertThatValue(buttonBox.x + buttonBox.width).isLessThanOrEqualTo(actionBox.x + actionBox.width)

        deleteButton.click()
        assertThat(page.locator("dialog[open]#confirm-dialog")).isVisible()
    }
}
