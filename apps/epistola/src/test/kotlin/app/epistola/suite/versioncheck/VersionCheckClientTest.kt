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
    fun `fetch sends current version header and parses releases document`() {
        val seenVersion = AtomicReference<String?>()
        val seenUserAgent = AtomicReference<String?>()
        server.createContext("/.well-known/epistola/releases.json") { exchange ->
            seenVersion.set(exchange.requestHeaders.getFirst("X-Epistola-Suite-Version"))
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
        val document = client.fetch("http://127.0.0.1:$port/.well-known/epistola/releases.json", "1.0.0-RC3")

        assertThat(seenVersion.get()).isEqualTo("1.0.0-RC3")
        assertThat(seenUserAgent.get()).isEqualTo("epistola-suite/1.0.0-RC3")
        assertThat(document.schemaVersion).isEqualTo(1)
        assertThat(document.products[VersionCheckService.PRODUCT_KEY]?.stable?.version).isEqualTo("1.0.0")
    }

    @Test
    fun `fetch treats missing releases document as metadata unavailable`() {
        server.createContext("/.well-known/epistola/releases.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val client = VersionCheckClient(VersionCheckConfiguration().versionCheckRestClient(VersionCheckProperties()))

        assertThatThrownBy {
            client.fetch("http://127.0.0.1:$port/.well-known/epistola/releases.json", "1.0.0")
        }.isInstanceOf(VersionMetadataUnavailableException::class.java)
            .hasMessageContaining("Release metadata not found")
    }
}
