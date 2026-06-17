package app.epistola.suite.ui

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.LoadState

/**
 * Shared, deterministic Playwright helpers for HTMX-driven UI tests.
 *
 * These exist to make the *correct* (race-free) thing the *easy* thing. Every UI
 * test should navigate via [BasePlaywrightTest.gotoAndReady], wait for HTMX swaps
 * via [htmxSettle], open dialogs/popovers via [openDialogByTrigger], and assert
 * with Playwright's web-first [assertThat]. The forbidden anti-patterns
 * (`page.waitForTimeout`, the `:visible` CSS pseudo, blind
 * `waitForSelector("…[open]")`, bare `page.navigate`) are enforced by
 * `UiTestHygieneTest`.
 *
 * Lives in the app test source set because Playwright is an app-test-only
 * dependency (not on `modules/testing`'s classpath).
 */
object PlaywrightHtmxSupport {

    /**
     * In-page HTMX activity bookkeeper. Installed once per page via
     * `addInitScript` (so it is present before any page script runs and survives
     * every navigation/swap). HTMX events bubble to `document`, so a single
     * document-level listener observes all requests.
     *
     * Tracks the number of in-flight requests and the timestamp of the last HTMX
     * activity (request start/end or settle). [htmxSettle] waits for *quiescence*:
     * zero in-flight requests AND a short quiet window with no new activity. The
     * quiet window is a debounce, not a wall-clock race against application
     * timing — it only absorbs the gap between `afterRequest` and the
     * `afterSettle` timer (htmx default settle delay is 20 ms) and the gap before
     * a chained `hx-trigger`-after-settle request starts.
     */
    const val HTMX_BOOKKEEPER_SCRIPT: String = """
        (() => {
            if (window.__htmxBookkeeperInstalled) return;
            window.__htmxBookkeeperInstalled = true;
            window.__htmxPending = 0;
            window.__htmxIdleSince = (performance && performance.now) ? performance.now() : Date.now();
            const now = () => ((performance && performance.now) ? performance.now() : Date.now());
            document.addEventListener('htmx:beforeRequest', () => {
                window.__htmxPending++;
                window.__htmxIdleSince = now();
            });
            document.addEventListener('htmx:afterRequest', () => {
                window.__htmxPending = Math.max(0, window.__htmxPending - 1);
                window.__htmxIdleSince = now();
            });
            document.addEventListener('htmx:afterSettle', () => {
                window.__htmxIdleSince = now();
            });
        })();
    """

    /** Quiet window (ms) with no HTMX activity that counts as "settled". */
    const val QUIET_WINDOW_MS: Int = 75
}

/**
 * Waits until HTMX is quiescent: no in-flight requests and no HTMX activity for a
 * short quiet window (covers the settle-delay timer and any chained
 * `hx-trigger`-after-settle request starting). No-ops immediately when the page
 * has no HTMX (bookkeeper absent).
 *
 * Strictly more robust than coupling a `waitForFunction` to a specific DOM count,
 * and content-agnostic. Only needed before **non-retrying** reads (`innerText`,
 * `count()`, `getAttribute`); Playwright web-first assertions already retry, so
 * prefer asserting the expected post-swap state directly when you can.
 *
 * Limitation: a page with *continuous* HTMX polling (`hx-trigger="every …"`)
 * would never go quiet — no in-scope page does this.
 */
fun Page.htmxSettle() {
    waitForFunction(
        """
        (quietMs) => {
            if (window.__htmxPending === undefined) return true;
            if (window.__htmxPending !== 0) return false;
            const now = (performance && performance.now) ? performance.now() : Date.now();
            return (now - window.__htmxIdleSince) > quietMs;
        }
        """,
        PlaywrightHtmxSupport.QUIET_WINDOW_MS,
    )
}

/**
 * Clicks [trigger] and waits until [dialogSelector] refers to a dialog/popover
 * that is genuinely open *and rendered* (non-zero box). Replaces every
 * `click()` + blind `waitForSelector("…[open]")`, which is the #418 flake family:
 *
 *  - First waits for the `load` state so deferred/module scripts that bind the
 *    open handler have executed before the click (the #418-A binding race).
 *  - Asserts the trigger is actionable (web-first) and scrolls it into view.
 *  - Waits for the dialog to be open via `:modal` / `[open]` / `:popover-open`
 *    **and** to have a non-zero bounding box — directly defeating the #418-B
 *    "open but height 0 because async HTMX content hasn't arrived yet" case.
 *
 * @return a [Locator] for the opened dialog, for chaining.
 */
fun Page.openDialogByTrigger(trigger: Locator, dialogSelector: String): Locator {
    waitForLoadState(LoadState.LOAD)
    assertThat(trigger).isVisible()
    trigger.scrollIntoViewIfNeeded()
    trigger.click()
    waitForFunction(
        """
        (sel) => {
            const el = document.querySelector(sel);
            if (!el) return false;
            const open = el.matches(':modal') || el.hasAttribute('open') ||
                (el.matches && el.matches(':popover-open'));
            if (!open) return false;
            const r = el.getBoundingClientRect();
            return r.height > 0 && r.width > 0;
        }
        """,
        dialogSelector,
    )
    return locator(dialogSelector)
}

/**
 * Named, retrying assertion that this locator matches exactly [expected]
 * elements. Use a locator that *structurally* selects only the shown elements
 * (e.g. `.card:not([hidden])`) — never the query-time `:visible` CSS pseudo,
 * which is evaluated once and does not auto-wait.
 */
fun Locator.assertVisibleCount(expected: Int) {
    assertThat(this).hasCount(expected)
}

/**
 * Retrying assertion that [dialogSelector] is a *genuine native modal* — an
 * element promoted into the browser's **top layer** by `showModal()`, as the
 * `:modal` pseudo-class reflects.
 *
 * This is strictly stronger than visibility or the `[open]` attribute: `:modal`
 * matches **only** a top-layer modal, so it distinguishes the real thing from
 * (a) server-rendered `<dialog open>` markup, (b) a non-modal `dialog.show()`,
 * and (c) plain HTML that merely contains the dialog. Modal state is browser
 * runtime state, not markup — `:modal` is the canonical way to observe it (there
 * is no `document.topLayer` API).
 *
 * Uses `waitForFunction` (the same mechanism `openDialogByTrigger` uses) so it
 * auto-retries; `:modal` is evaluated by the browser's own `matches`, not the
 * banned Playwright `:visible` selector engine.
 */
fun Page.assertNativeModalOpen(dialogSelector: String) {
    waitForFunction(
        """
        (sel) => {
            const el = document.querySelector(sel);
            return !!el && el.matches(':modal');
        }
        """,
        dialogSelector,
    )
}

/**
 * Retrying assertion that [dialogSelector] is **not** in the top layer — either
 * gone from the DOM or present-but-closed (`dialog.close()` removes it from the
 * top layer, so `:modal` no longer matches). The precise complement of
 * [assertNativeModalOpen] for "the modal layer is dismissed".
 */
fun Page.assertNoNativeModal(dialogSelector: String) {
    waitForFunction(
        """
        (sel) => {
            const el = document.querySelector(sel);
            return !el || !el.matches(':modal');
        }
        """,
        dialogSelector,
    )
}
