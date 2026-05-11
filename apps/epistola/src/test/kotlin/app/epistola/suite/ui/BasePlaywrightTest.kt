package app.epistola.suite.ui

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Tracing
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
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
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
    }

    protected fun baseUrl() = "http://localhost:$port"

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
        private val lastTestFailed: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

        private val unsafeChars = Regex("[^A-Za-z0-9._-]+")

        private fun sanitize(name: String): String = unsafeChars.replace(name, "_").take(120)
    }
}
