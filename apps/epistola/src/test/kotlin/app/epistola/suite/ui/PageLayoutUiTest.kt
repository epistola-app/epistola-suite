// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.default
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
    fun `a list's labelled row actions fit inside their column`() {
        val tenant = createTenant("Row Action Layout")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("welcome"), CatalogId.default(TenantId(tenant.id))), "Welcome").execute()
        }

        gotoAndReady("/tenants/${tenant.id}/catalogs/default/templates")

        val buttons = page.locator("td.actions .ep-btn")
        assertThat(buttons.first()).isVisible()
        @Suppress("UNCHECKED_CAST")
        val overflow = page.locator("td.actions").evaluateAll(
            """
            cells => cells.flatMap(cell => {
                const box = cell.getBoundingClientRect();
                return [...cell.querySelectorAll('.ep-btn')].map(b => b.getBoundingClientRect().right - box.right);
            })
            """,
        ) as List<Number>
        assertThatValue(overflow.map { it.toDouble() }).`as`("no button runs past its cell").allMatch { it <= 0.5 }
    }

    @Test
    fun `the publish dialog keeps Publish in view however many changes it lists`() {
        val tenant = createTenant("Publish Dialog Layout")
        withMediator {
            (1..14).forEach { CreateDocumentTemplate(TemplateId(TemplateKey.of("letter-$it"), CatalogId.default(TenantId(tenant.id))), "Letter $it").execute() }
        }
        page.setViewportSize(1280, 720)

        gotoAndReady("/tenants/${tenant.id}/catalogs/default/draft")
        page.openDialogByTrigger(page.locator("[data-testid='catalog-release-open']"), "#release-dialog")

        val publish = page.locator("dialog[open]#release-dialog [data-testid='release-submit']")
        assertThat(publish).isInViewport()
        assertThat(page.locator("dialog[open]#release-dialog [data-testid='publish-change']")).hasCount(14)
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
    fun `at the end of a page the feedback button sits below the content, not on it`() {
        val tenant = createTenant("Feedback Button Layout")
        // Short enough that the tenant home scrolls, so the button floats over content until the end.
        page.setViewportSize(1280, 480)

        gotoAndReady("/tenants/${tenant.id}")
        val fab = page.locator("#feedback-fab")
        assertThat(fab).isVisible()
        page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")

        val contentBottom = (
            page.evaluate(
                """
                () => Math.max(...[...document.querySelectorAll('main.main-content > *')]
                    .map(e => e.getBoundingClientRect()).filter(r => r.height > 0).map(r => r.bottom))
                """,
            ) as Number
            ).toDouble()
        val fabTop = requireNotNull(fab.boundingBox()).y
        assertThatValue(contentBottom).`as`("the content ends above the button").isLessThanOrEqualTo(fabTop)
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
