package app.epistola.suite.ui

import app.epistola.suite.BaseIntegrationTest
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
    ],
)
@Tag("ui")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BasePlaywrightTest : BaseIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

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
        page = browser.newPage()
    }

    @AfterEach
    fun closePage() {
        page.close()
    }

    protected fun baseUrl() = "http://localhost:$port"
}
