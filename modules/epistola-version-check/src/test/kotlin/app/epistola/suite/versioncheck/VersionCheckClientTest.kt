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
import java.util.UUID
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
    fun `fetch sends current version and installation id headers and parses releases document`() {
        val seenVersion = AtomicReference<String?>()
        val seenInstallationId = AtomicReference<String?>()
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
                      }
                    }
                  }
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client = VersionCheckClient(VersionCheckConfiguration().versionCheckRestClient(VersionCheckProperties()))
        val installationId = UUID.fromString("00000000-0000-4000-8000-000000000123")
        val document = client.fetch(
            "http://127.0.0.1:$port/.well-known/epistola/releases.json",
            "1.0.0-RC3",
            installationId,
        )

        assertThat(seenVersion.get()).isEqualTo("1.0.0-RC3")
        assertThat(seenInstallationId.get()).isEqualTo("00000000-0000-4000-8000-000000000123")
        assertThat(seenUserAgent.get()).isEqualTo("epistola-suite/1.0.0-RC3")
        assertThat(document.schemaVersion).isEqualTo(1)
        val product = document.products[VersionCheckService.PRODUCT_KEY]
        assertThat(product?.stable?.version).isEqualTo("1.0.0")
        assertThat(product?.support?.minVersion).isEqualTo("0.9.0")
        assertThat(product?.support?.until).isEqualTo("2027-01-31")
    }

    @Test
    fun `fetch treats missing releases document as metadata unavailable`() {
        server.createContext("/.well-known/epistola/releases.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val client = VersionCheckClient(VersionCheckConfiguration().versionCheckRestClient(VersionCheckProperties()))

        assertThatThrownBy {
            client.fetch(
                "http://127.0.0.1:$port/.well-known/epistola/releases.json",
                "1.0.0",
                UUID.fromString("00000000-0000-4000-8000-000000000123"),
            )
        }.isInstanceOf(VersionMetadataUnavailableException::class.java)
            .hasMessageContaining("Release metadata not found")
    }
}
