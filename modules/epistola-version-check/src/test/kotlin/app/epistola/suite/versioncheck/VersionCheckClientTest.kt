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
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class VersionCheckClientTest {
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
    fun `fetch is anonymous and parses the releases document`() {
        val seenVersion = AtomicReference<String?>("unset")
        val seenInstallationId = AtomicReference<String?>("unset")
        val seenUserAgent = AtomicReference<String?>()
        server.createContext("/.well-known/epistola/releases.json") { exchange ->
            seenVersion.set(exchange.requestHeaders.getFirst("X-Epistola-Suite-Version"))
            seenInstallationId.set(exchange.requestHeaders.getFirst("X-Epistola-Installation-Id"))
            seenUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            val body = """
                {
                  "schemaVersion": 1,
                  "products": {
                    "epistola-suite": {
                      "stable": {
                        "version": "1.0.0",
                        "releaseUrl": "https://epistola.app/releases/epistola-suite/1.0.0",
                        "changelogUrl": "https://epistola.app/changelog"
                      },
                      "support": {
                        "minVersion": "0.9.0",
                        "until": "2027-01-31"
                      },
                      "advisories": [
                        {
                          "id": "EPIS-2026-001",
                          "severity": "medium",
                          "affected": [{ "introduced": "0.0.0", "lastAffected": "1.0.1" }]
                        }
                      ]
                    }
                  }
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client = VersionCheckClient(VersionCheckConfiguration().versionCheckRestClient(VersionCheckProperties()))
        val document = client.fetch("http://127.0.0.1:$port/.well-known/epistola/releases.json")

        assertThat(seenVersion.get()).isNull()
        assertThat(seenInstallationId.get()).isNull()
        assertThat(seenUserAgent.get()).isEqualTo("epistola-suite")
        assertThat(document.schemaVersion).isEqualTo(1)
        val product = document.products[VersionCheckService.PRODUCT_KEY]
        assertThat(product?.stable?.version).isEqualTo("1.0.0")
        assertThat(product?.support?.minVersion).isEqualTo("0.9.0")
        assertThat(product?.support?.until).isEqualTo("2027-01-31")
        assertThat(product?.advisories?.single()?.affected?.single()?.lastAffected).isEqualTo("1.0.1")
    }

    @Test
    fun `fetch treats missing releases document as metadata unavailable`() {
        server.createContext("/.well-known/epistola/releases.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val client = VersionCheckClient(VersionCheckConfiguration().versionCheckRestClient(VersionCheckProperties()))

        assertThatThrownBy {
            client.fetch("http://127.0.0.1:$port/.well-known/epistola/releases.json")
        }.isInstanceOf(VersionMetadataUnavailableException::class.java)
            .hasMessageContaining("Release metadata not found")
    }

    @Test
    fun `fetch ignores fields it does not know, such as the live releases json advisories`() {
        // The exact document epistola.app serves since website v2.3.0 (advisories added, plus a
        // second product). Older Suites must keep parsing it.
        server.createContext("/.well-known/epistola/releases.json") { exchange ->
            val body = """
                {"schemaVersion":1,"products":{"epistola-suite":{"stable":{"version":"1.3.0","releaseUrl":"https://github.com/epistola-app/epistola-suite/releases/tag/v1.3.0","changelogUrl":null,"publishedAt":"2026-09-28T12:00:00Z"},"support":{"minVersion":"1.0.0"},"advisories":[{"id":"EPIS-2026-001","aliases":["GHSA-69jx-3ffj-pjw9"],"severity":"medium","summary":"Stored HTML injection","published":"2026-06-25T16:18:42Z","affected":[{"introduced":"0.0.0","lastAffected":"1.0.1"}],"fixedIn":null,"url":"https://github.com/epistola-app/epistola-suite/blob/main/vulnerabilities/2026-06-25-epis-2026-001.md"}],"unknownField":{"x":1}},"valtimo-epistola-plugin":{"stable":{"version":"1.2.0","releaseUrl":"https://central.sonatype.com/artifact/app.epistola.valtimo/epistola-plugin/1.2.0","changelogUrl":null,"publishedAt":null}}}}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client = VersionCheckClient(VersionCheckConfiguration().versionCheckRestClient(VersionCheckProperties()))
        val document = client.fetch("http://127.0.0.1:$port/.well-known/epistola/releases.json")

        assertThat(document.products[VersionCheckService.PRODUCT_KEY]?.stable?.version).isEqualTo("1.3.0")
    }
}
