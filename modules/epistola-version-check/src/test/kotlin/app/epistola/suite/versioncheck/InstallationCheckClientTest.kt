// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class InstallationCheckClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: InstallationCheckClient
    private val id = UUID.fromString("01a0f4c2-c400-7abc-8def-0123456789ab")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        client = InstallationCheckClient(
            InstallationCheckProperties(url = "http://127.0.0.1:${server.address.port}/installation/{id}.json"),
        )
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun respond(status: Int, body: String, seen: AtomicReference<Map<String, String?>>? = null) {
        server.createContext("/installation/") { exchange ->
            seen?.set(
                mapOf(
                    "path" to exchange.requestURI.path,
                    "version" to exchange.requestHeaders.getFirst("X-Epistola-Suite-Version"),
                    "installationHeader" to exchange.requestHeaders.getFirst("X-Epistola-Installation-Id"),
                    "userAgent" to exchange.requestHeaders.getFirst("User-Agent"),
                ),
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `sends the id in the path and the version only when it is shared`() {
        val seen = AtomicReference<Map<String, String?>>()
        respond(200, """{"schemaVersion":1,"installation":{"id":"x","registered":false}}""", seen)

        val (withVersionUrl, withVersion) = client.request(id, "1.4.0")
        client.fetch(withVersionUrl, withVersion)
        assertThat(seen.get()["path"]).isEqualTo("/installation/$id.json")
        assertThat(seen.get()["version"]).isEqualTo("1.4.0")
        assertThat(seen.get()["installationHeader"]).isNull()
        assertThat(seen.get()["userAgent"]).isEqualTo("epistola-suite")

        val (url, headers) = client.request(id, null)
        client.fetch(url, headers)
        assertThat(seen.get()["version"]).isNull()
        assertThat(headers).doesNotContainKey("X-Epistola-Suite-Version")
    }

    @Test
    fun `parses a registered installation`() {
        respond(
            200,
            """
            {"schemaVersion":1,"installation":{"id":"$id","registered":true,"status":"supported","route":"partner-managed",
             "supportedUntil":"2027-12-31","supportParty":{"name":"Acme Hosting","url":"https://acme.test","supportContact":"https://acme.test/support"},
             "versionPolicy":{"minVersion":"1.0.0"},"page":"https://epistola.app/nl/installation/$id","futureField":true}}
            """.trimIndent(),
        )
        val (url, headers) = client.request(id, "1.4.0")
        val (code, result) = client.fetch(url, headers)

        assertThat(code).isEqualTo(200)
        assertThat(result).isEqualTo(
            InstallationCheckResult(
                registered = true,
                status = "supported",
                route = "partner-managed",
                supportedUntil = "2027-12-31",
                supportPartyName = "Acme Hosting",
                supportPartyUrl = "https://acme.test",
                supportContact = "https://acme.test/support",
                minVersion = "1.0.0",
                page = "https://epistola.app/nl/installation/$id",
            ),
        )
    }

    @Test
    fun `drops anything unexpected instead of trusting it`() {
        val result = InstallationCheckClient.parse(
            JsonMapper.builder().build().readTree(
                """
                {"installation":{"registered":true,"status":"<b>gold</b>","route":"moon","supportedUntil":"2027-02-30",
                 "supportParty":{"name":"${"A".repeat(500)}\u0007","url":"javascript:alert(1)","supportContact":"http://plain.test"},
                 "versionPolicy":{"minVersion":"latest"},"page":"data:text/html,hi"}}
                """.trimIndent(),
            ),
        )

        assertThat(result.status).isNull()
        assertThat(result.route).isNull()
        assertThat(result.supportedUntil).isNull()
        assertThat(result.supportPartyName).hasSize(100).doesNotContain("\u0007")
        assertThat(result.supportPartyUrl).isNull()
        assertThat(result.supportContact).isNull()
        assertThat(result.minVersion).isNull()
        assertThat(result.page).isNull()
    }

    @Test
    fun `reports non-200 answers without a result`() {
        respond(503, """{"error":"installation register unavailable"}""")
        val (url, headers) = client.request(id, null)
        assertThat(client.fetch(url, headers)).isEqualTo(503 to null)
    }

    @Test
    fun `refuses oversized or malformed responses`() {
        respond(200, "{\"installation\":{\"page\":\"${"x".repeat(InstallationCheckClient.MAX_BYTES)}\"}}")
        val (url, headers) = client.request(id, null)
        assertThatThrownBy { client.fetch(url, headers) }.isInstanceOf(InstallationCheckException::class.java)

        assertThatThrownBy { InstallationCheckClient.parse(JsonMapper.builder().build().readTree("""{"schemaVersion":1}""")) }
            .isInstanceOf(InstallationCheckException::class.java)
    }
}
