// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.Route
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [htmxSettle] counts the requests in flight. When a swap replaces an element whose request is still
 * in flight, htmx cleans the element up and never reports that request's end: no
 * `htmx:afterRequest`, no abort. The embedding bridge's `navigate` swaps the whole body while the
 * page's own load requests (the `ws-status` bar) may still run, which is how
 * `EmbeddingBridgeUiTest` timed out under load (T1). Counting by the request itself keeps the count
 * right, so the page still settles.
 */
class HtmxSettleUiTest : BasePlaywrightTest() {

    @Test
    fun `a page settles after a swap replaces an element whose request was in flight`() {
        val tenant = createTenant("Htmx Settle")
        gotoAndReady("/tenants/${tenant.id}/catalogs/default/templates")

        // One request held open until the swap has replaced the element that made it.
        var held: Route? = null
        page.route("**/__held-request") { held = it }
        page.route("**/__replacement") { it.fulfill(Route.FulfillOptions().setStatus(200).setBody("<p id='replaced'>replaced</p>")) }
        page.evaluate(
            """
            () => {
                const box = document.createElement('div');
                box.id = 'held-box';
                box.innerHTML = '<div id="held-target"></div>';
                document.querySelector('main').appendChild(box);
                htmx.process(box);
                htmx.ajax('GET', '/__held-request', { source: '#held-target', target: '#held-target' });
            }
            """,
        )
        page.waitForCondition { held != null }

        page.evaluate("() => htmx.ajax('GET', '/__replacement', { target: '#held-box', swap: 'innerHTML' })")
        page.waitForCondition { page.evaluate("() => !!document.getElementById('replaced') && !document.getElementById('held-target')") == true }
        held!!.fulfill(Route.FulfillOptions().setStatus(200).setBody("late"))

        page.htmxSettle()
        assertThat(page.evaluate("() => window.__htmxPending")).isEqualTo(0)
    }
}
