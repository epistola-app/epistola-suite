package app.epistola.suite.ui

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
import com.microsoft.playwright.options.LoadState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.nio.file.Paths

@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
    ],
)
@Tag("ui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(BasePlaywrightTest.FailureRecorder::class)
abstract class BasePlaywrightTest : BaseIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var context: BrowserContext

    protected lateinit var page: Page

    @BeforeAll
    fun launchBrowser() {
        playwright = Playwright.create()
        browser = playwright.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(true)
                // CI hardening: GitHub's ubuntu runners have a tiny /dev/shm; without
                // --disable-dev-shm-usage Chromium tab crashes there surface as random
                // selector timeouts (the #418 flake family). --no-sandbox is standard in
                // CI containers; --disable-gpu removes a headless rendering variance source.
                .setArgs(listOf("--disable-dev-shm-usage", "--no-sandbox", "--disable-gpu")),
        )
    }

    @AfterAll
    fun closeBrowser() {
        browser.close()
        playwright.close()
    }

    @BeforeEach
    fun createPage() {
        context = browser.newContext()
        context.tracing().start(
            Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true),
        )
        page = context.newPage()
        // Install the HTMX activity bookkeeper before any page script runs (and on
        // every subsequent navigation/swap) so htmxSettle() can observe in-flight
        // requests. Same addInitScript technique FeedbackScreenshotUiTest uses.
        page.addInitScript(PlaywrightHtmxSupport.HTMX_BOOKKEEPER_SCRIPT)
        // ADR 0010: a blocked script under the strict CSP fails silently (console
        // error only) — collect violations and fail the test in closePage() so a
        // missed migration surfaces as a red test instead of a dead button.
        cspViolations.clear()
        page.onConsoleMessage { message ->
            if (message.text().contains("Content Security Policy")) {
                cspViolations.add(message.text())
            }
        }
        // Centralized, explicit timeouts (single source of truth — replaces Playwright's
        // implicit 30s default). Actions/assertions fail fast at 15s so a real hang is
        // captured in the trace instead of holding CI for 30s; navigation stays generous
        // for Spring cold-start paths.
        context.setDefaultTimeout(ACTION_TIMEOUT_MS)
        page.setDefaultTimeout(ACTION_TIMEOUT_MS)
        page.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS)
    }

    @AfterEach
    fun closePage(testInfo: TestInfo) {
        val failed = lastTestFailed.get()
        lastTestFailed.remove()
        if (failed) {
            val tracePath = Paths.get(
                "build",
                "playwright-traces",
                "${this::class.simpleName}_${sanitize(testInfo.displayName)}.zip",
            )
            tracePath.parent?.toFile()?.mkdirs()
            context.tracing().stop(Tracing.StopOptions().setPath(tracePath))
        } else {
            context.tracing().stop()
        }
        page.close()
        context.close()

        val violations = cspViolations.toList()
        cspViolations.clear()
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "Content-Security-Policy violations during '${testInfo.displayName}' " +
                    "(ADR 0010 — an inline script or handler slipped past the template rules):\n" +
                    violations.joinToString("\n"),
            )
        }
    }

    private val cspViolations = mutableListOf<String>()

    protected fun baseUrl() = "http://localhost:$port"

    /**
     * Navigates to [path] (relative to the test server root) and waits until the
     * document and its inline `<script>` tags are parsed. The only sanctioned way to
     * navigate in UI tests — never call [Page.navigate] directly (enforced by
     * `UiTestHygieneTest`).
     *
     * Uses `DOMCONTENTLOADED`, not `NETWORKIDLE`: htmx `hx-trigger="load"` requests
     * keep the network busy, so `NETWORKIDLE` is itself flaky. The "JS finished
     * binding" guarantee is layered on top by the helpers in `PlaywrightHtmxSupport`.
     */
    protected fun gotoAndReady(path: String) {
        page.navigate(baseUrl() + path)
        page.waitForLoadState(LoadState.DOMCONTENTLOADED)
    }

    /**
     * Records per-test pass/fail before @AfterEach runs so trace persistence can branch on outcome.
     * TestWatcher fires after @AfterEach in JUnit 5, which would be too late.
     */
    class FailureRecorder : AfterTestExecutionCallback {
        override fun afterTestExecution(context: ExtensionContext) {
            if (context.executionException.isPresent) {
                lastTestFailed.set(true)
            }
        }
    }

    companion object {
        /** Default budget for actions and web-first assertions. */
        const val ACTION_TIMEOUT_MS: Double = 15_000.0

        /** Default budget for navigation — generous for Spring cold-start paths. */
        const val NAVIGATION_TIMEOUT_MS: Double = 30_000.0

        private val lastTestFailed: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        private val unsafeChars = Regex("[^A-Za-z0-9._-]+")

        private fun sanitize(name: String): String = unsafeChars.replace(name, "_").take(120)
    }
}
