package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.commands.CodeListNotRefreshableException
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.RefreshCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end coverage of `RefreshCodeList` against a real HTTP server.
 *
 * Spins up a tiny `com.sun.net.httpserver.HttpServer` on a random port. Each
 * test installs its own handler so we can assert on inbound request headers
 * (auth) and shape responses (success, 5xx, etc.) without pulling in WireMock.
 *
 * `epistola.codelists.allow-http=true` is set here so the test can drive the
 * fetcher with `http://localhost:<port>/…` — production keeps the default
 * (`https` only).
 */
@TestPropertySource(properties = ["epistola.codelists.allow-http=true"])
class RefreshCodeListTest : IntegrationTestBase() {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `refresh fetches entries from URL and updates last_refreshed_at`() {
        installJsonHandler(
            """
            [
              { "code": "eu", "label": "Europe", "sortOrder": 1 },
              { "code": "us", "label": "United States", "sortOrder": 2 }
            ]
            """.trimIndent(),
        )

        val tenant = createTenant("Refresh1")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("regions"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Regions",
                sourceType = CodeListSource.URL,
                sourceUrl = "http://127.0.0.1:$port/regions.json",
                authType = AuthType.NONE,
            ).execute()

            // Pre-condition: no entries yet, no refresh stamp.
            assertThat(ListCodeListEntries(id).query()).isEmpty()
            assertThat(GetCodeList(id).query()!!.lastRefreshedAt).isNull()

            RefreshCodeList(id).execute()

            val entries = ListCodeListEntries(id).query()
            assertThat(entries).extracting<String> { it.code }.containsExactly("eu", "us")
            assertThat(entries.first { it.code == "eu" }.label).isEqualTo("Europe")

            val refreshed = GetCodeList(id).query()!!
            assertThat(refreshed.lastRefreshedAt).isNotNull()
            assertThat(refreshed.lastRefreshError).isNull()
        }
    }

    @Test
    fun `refresh failure keeps existing entries and records last_refresh_error`() {
        // First response: success; later: 500. We seed entries with the first
        // refresh, then break the source to force a failure on the second.
        val responses = mutableListOf(
            jsonOk(
                """
                [{ "code": "initial", "label": "Initial entry" }]
                """.trimIndent(),
            ),
            serverError(),
        )
        installSequentialHandler(responses)

        val tenant = createTenant("Refresh2")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("flaky"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Flaky",
                sourceType = CodeListSource.URL,
                sourceUrl = "http://127.0.0.1:$port/flaky.json",
            ).execute()

            // First refresh seeds the entry.
            RefreshCodeList(id).execute()
            assertThat(ListCodeListEntries(id).query()).extracting<String> { it.code }.containsExactly("initial")

            // Second refresh: server returns 500. Entries should be preserved
            // and last_refresh_error populated; the command must not throw.
            RefreshCodeList(id).execute()

            assertThat(ListCodeListEntries(id).query())
                .extracting<String> { it.code }
                .containsExactly("initial")

            val state = GetCodeList(id).query()!!
            assertThat(state.lastRefreshError).isNotNull()
        }
    }

    @Test
    fun `refresh with over-length source entries keeps existing entries and records last_refresh_error`() {
        val responses = mutableListOf(
            jsonOk(
                """
                [{ "code": "initial", "label": "Initial entry" }]
                """.trimIndent(),
            ),
            jsonOk(
                """
                [{ "code": "${"x".repeat(65)}", "label": "Too long" }]
                """.trimIndent(),
            ),
        )
        installSequentialHandler(responses)

        val tenant = createTenant("RefreshLong")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("longsource"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Long source",
                sourceType = CodeListSource.URL,
                sourceUrl = "http://127.0.0.1:$port/longsource.json",
            ).execute()

            RefreshCodeList(id).execute()
            assertThat(ListCodeListEntries(id).query()).extracting<String> { it.code }.containsExactly("initial")

            RefreshCodeList(id).execute()

            assertThat(ListCodeListEntries(id).query())
                .extracting<String> { it.code }
                .containsExactly("initial")

            val state = GetCodeList(id).query()!!
            assertThat(state.lastRefreshError).contains("Entry codes must be 64 characters or less")
        }
    }

    @Test
    fun `refresh sends Bearer authorization when auth_type is BEARER`() {
        val seenAuth = AtomicReference<String?>()
        installCapturingHandler(
            captureHeader = "Authorization",
            into = seenAuth,
            body = """[{ "code": "ok", "label": "OK" }]""",
        )

        val tenant = createTenant("Refresh3")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("authd"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "AuthD",
                sourceType = CodeListSource.URL,
                sourceUrl = "http://127.0.0.1:$port/authd.json",
                authType = AuthType.BEARER,
                credential = "secret-token-xyz",
            ).execute()

            RefreshCodeList(id).execute()

            assertThat(seenAuth.get()).isEqualTo("Bearer secret-token-xyz")
            assertThat(ListCodeListEntries(id).query()).extracting<String> { it.code }.containsExactly("ok")
        }
    }

    @Test
    fun `refresh sends X-API-Key when auth_type is API_KEY`() {
        val seenApiKey = AtomicReference<String?>()
        installCapturingHandler(
            captureHeader = "X-API-Key",
            into = seenApiKey,
            body = """[{ "code": "ok", "label": "OK" }]""",
        )

        val tenant = createTenant("Refresh4")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("authd"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "AuthD",
                sourceType = CodeListSource.URL,
                sourceUrl = "http://127.0.0.1:$port/authd.json",
                authType = AuthType.API_KEY,
                credential = "k-12345",
            ).execute()

            RefreshCodeList(id).execute()

            assertThat(seenApiKey.get()).isEqualTo("k-12345")
        }
    }

    @Test
    fun `refresh of an INLINE code list throws CodeListNotRefreshableException`() {
        val tenant = createTenant("Refresh5")
        val tenantId = TenantId(tenant.id)
        val id = CodeListId(CodeListKey.of("manual"), CatalogId.default(tenantId))

        withMediator {
            CreateCodeList(
                id = id,
                displayName = "Manual",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("a", "A")),
            ).execute()

            assertThatThrownBy { RefreshCodeList(id).execute() }
                .isInstanceOf(CodeListNotRefreshableException::class.java)
                .hasMessageContaining("INLINE")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP server helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun installJsonHandler(body: String) {
        // Wildcard "/" matches any path under the server's address.
        server.createContext("/", HttpHandler { exchange -> respond(exchange, 200, "application/json", body) })
    }

    private fun installCapturingHandler(captureHeader: String, into: AtomicReference<String?>, body: String) {
        server.createContext(
            "/",
            HttpHandler { exchange ->
                into.set(exchange.requestHeaders.getFirst(captureHeader))
                respond(exchange, 200, "application/json", body)
            },
        )
    }

    private fun installSequentialHandler(responses: List<StubResponse>) {
        val queue = responses.toMutableList()
        server.createContext(
            "/",
            HttpHandler { exchange ->
                val r = if (queue.size > 1) queue.removeAt(0) else queue[0]
                respond(exchange, r.status, r.contentType, r.body)
            },
        )
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private data class StubResponse(val status: Int, val contentType: String, val body: String)

    private fun jsonOk(body: String) = StubResponse(200, "application/json", body)
    private fun serverError() = StubResponse(500, "text/plain", "boom")
}
