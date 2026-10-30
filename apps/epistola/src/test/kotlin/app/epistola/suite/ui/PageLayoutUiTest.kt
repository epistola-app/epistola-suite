// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat as assertThatValue

/**
 * Layout the browser decides: what fits beside what, and what scrolls where. Each case is a page
 * that once broke at an ordinary viewport width.
 */
class PageLayoutUiTest : BasePlaywrightTest() {

    @Test
    fun `a page header with many actions keeps its title whole, wrapping the actions instead`() {
        val tenant = createTenant("Page Header Layout")
        // Wider than the stacking breakpoint (56rem), so title and actions share a row.
        page.setViewportSize(960, 720)

        gotoAndReady("/tenants/${tenant.id}/catalogs")

        val title = page.locator("[data-testid='page-title']")
        val actions = page.locator(".page-header > .page-actions")
        // Not isVisible() on the title: squeezed to zero width it counts as hidden, which hides the cause.
        assertThat(actions).isVisible()

        val titleOverflow = (title.evaluate("element => element.scrollWidth - element.clientWidth") as Number).toDouble()
        assertThatValue(titleOverflow).`as`("the title is not cut by its own box").isLessThanOrEqualTo(0.0)

        val titleBox = requireNotNull(title.boundingBox())
        val actionsBox = requireNotNull(actions.boundingBox())
        val besideIt = titleBox.x + titleBox.width <= actionsBox.x
        val belowIt = actionsBox.y >= titleBox.y + titleBox.height
        assertThatValue(besideIt || belowIt).`as`("the actions do not cover the title").isTrue()
    }

    @Test
    fun `the catalogs list shows whether each catalog can be changed without cutting the badge`() {
        val tenant = createTenant("Catalog Badge Layout")

        gotoAndReady("/tenants/${tenant.id}/catalogs")

        val cells = page.locator("td:has([data-testid='catalog-list-editable'], [data-testid='catalog-list-read-only'])")
        assertThat(cells.first()).isVisible()
        @Suppress("UNCHECKED_CAST")
        val overflows = cells.evaluateAll("cells => cells.map(c => c.scrollWidth - c.clientWidth)") as List<Number>
        assertThatValue(overflows.map { it.toDouble() }).allMatch { it <= 0.0 }
    }

    @Test
    fun `the What's New card wraps on a phone instead of running under View details`() {
        val tenant = createTenant("Whats New Layout")
        page.setViewportSize(390, 844)

        gotoAndReady("/tenants/${tenant.id}")

        val info = page.locator("#whats-new-banner .whats-new-info")
        val actions = page.locator("#whats-new-banner .whats-new-actions")
        assertThat(info).isVisible()

        val infoBox = requireNotNull(info.boundingBox())
        val actionsBox = requireNotNull(actions.boundingBox())
        val besideIt = infoBox.x + infoBox.width <= actionsBox.x
        val belowIt = actionsBox.y >= infoBox.y + infoBox.height
        assertThatValue(besideIt || belowIt).`as`("View details does not cover the text").isTrue()
        val infoOverflow = (info.evaluate("element => element.scrollWidth - element.clientWidth") as Number).toDouble()
        assertThatValue(infoOverflow).`as`("the text stays inside its box").isLessThanOrEqualTo(0.0)
    }

    @Test
    fun `the cluster page keeps its tables inside the page and writes times in the app's one shape`() {
        val tenant = createTenant("Cluster Layout")

        gotoAndReady("/tenants/${tenant.id}/cluster")

        val viewportWidth = (page.evaluate("() => document.documentElement.clientWidth") as Number).toDouble()
        val pageWidth = (page.evaluate("() => document.documentElement.scrollWidth") as Number).toDouble()
        assertThatValue(pageWidth).`as`("nothing runs past the page's edge").isLessThanOrEqualTo(viewportWidth)

        @Suppress("UNCHECKED_CAST")
        val times = page.locator("[data-local-time]").evaluateAll("elements => elements.map(e => e.textContent.trim())") as List<String>
        assertThatValue(times).isNotEmpty()
        assertThatValue(times).allMatch { it.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}(:\d{2})?""")) }
        assertThat(page.locator("[data-testid='cluster-scheduled-tasks-scroll']")).isVisible()
    }
}
