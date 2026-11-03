// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The page's own heading rule in `shell.css` (`.main-content h2`) styles a bare `<h2>` in page content
 * and nothing more: a component's heading — a card header, a dialog header — keeps the size and margin
 * its own rule gives it. That rule loads last in the same layer, so at equal specificity it used to win
 * over a card header's.
 */
class PageHeadingCascadeUiTest : BasePlaywrightTest() {

    /** "font-size margin-bottom" of [selector]'s first match, as the browser computed it. */
    private fun computed(selector: String): String = page.evaluate(
        "s => { const style = getComputedStyle(document.querySelector(s)); return style.fontSize + ' ' + style.marginBottom; }",
        selector,
    ) as String

    /** Puts [html] at the end of the page content, so it is styled as if a page had drawn it. */
    private fun addToPageContent(html: String) {
        page.evaluate("html => document.querySelector('.main-content').insertAdjacentHTML('beforeend', html)", html)
    }

    @Test
    fun `a card header keeps its own title size and margin`() {
        gotoAndReady("/profile")
        PlaywrightAssertions.assertThat(page.locator(".main-content .detail-section-header h2").first()).isVisible()

        // cards.css: --ep-text-base and no margin.
        assertThat(computed(".main-content .detail-section-header h2")).isEqualTo("16px 0px")
    }

    @Test
    fun `a dialog header in the page keeps its own title size and margin`() {
        gotoAndReady("/profile")
        addToPageContent("""<div class="ep-dialog" data-testid="probe"><div class="ep-dialog-header"><h2>Dialog title</h2></div></div>""")

        // dialogs.css: --ep-text-base and no margin. Its rule is nested under .ep-dialog, so it already
        // out-ranked the page rule; this holds it there.
        assertThat(computed("[data-testid=probe] h2")).isEqualTo("16px 0px")
    }

    @Test
    fun `a bare heading in the page still gets the page's heading style`() {
        gotoAndReady("/profile")
        addToPageContent("""<h2 data-testid="probe">Section</h2>""")

        // shell.css: --ep-text-xl with --ep-space-4 below.
        assertThat(computed("[data-testid=probe]")).isEqualTo("20px 16px")
    }
}
