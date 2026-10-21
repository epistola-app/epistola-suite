// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [OciRegistryClient] against a scripted registry: the parts a real `registry:2` cannot be made to
 * do (a Bearer token service, a wrong blob, a redirect to another host).
 */
class OciRegistryClientTest {

    private val mapper = JsonMapper.builder().build()
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun stop() = servers.forEach { it.stop(0) }

    /** A server answering every request with [handler]; its base URL. */
    private fun server(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange -> exchange.use { handler(it) } }
        server.start()
        servers += server
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun HttpExchange.reply(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(status, if (body.isEmpty() || requestMethod == "HEAD") -1 else body.size.toLong())
        if (body.isNotEmpty() && requestMethod != "HEAD") responseBody.write(body)
    }

    private fun client(url: String, username: String? = null, password: String? = null) = OciRegistryClient(
        CatalogRegistryProperties.Registry(name = "fake", url = url, username = username, password = password),
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        mapper,
        allowHttp = true,
        maxRedirects = 3,
    )

    private fun manifestFor(blob: OciDescriptor): ByteArray = ReleaseArtifact.manifest(
        mapper,
        ReleaseLabels("1.0.0", "v5:x", "r", "acme0", "letters"),
        OciDescriptor(OciMediaTypes.CONFIG, OciDigest.sha256("{}".toByteArray()), 2),
        blob,
    )

    @Test
    fun `a Bearer challenge is met with a token from the realm, asked with the configured credentials`() {
        val tokenRequests = CopyOnWriteArrayList<String>()
        val registryAuth = CopyOnWriteArrayList<String?>()
        lateinit var base: String
        base = server { exchange ->
            when {
                exchange.requestURI.path == "/token" -> {
                    tokenRequests += "${exchange.requestURI.rawQuery}|${exchange.requestHeaders.getFirst("Authorization")}"
                    exchange.reply(200, """{"access_token":"t0ken"}""".toByteArray())
                }

                exchange.requestHeaders.getFirst("Authorization") == "Bearer t0ken" -> {
                    registryAuth += "ok"
                    exchange.reply(200, """{"name":"acme0/letters","tags":["1.0.0"]}""".toByteArray())
                }

                else -> {
                    registryAuth += exchange.requestHeaders.getFirst("Authorization")
                    exchange.reply(401, headers = mapOf("WWW-Authenticate" to """Bearer realm="$base/token",service="fake",scope="repository:acme0/letters:pull""""))
                }
            }
        }

        val tags = client(base, "user", "pw").tags("acme0/letters")

        assertThat(tags).containsExactly("1.0.0")
        val basic = "Basic " + Base64.getEncoder().encodeToString("user:pw".toByteArray())
        assertThat(tokenRequests).containsExactly("service=fake&scope=repository%3Aacme0%2Fletters%3Apull|$basic")
        assertThat(registryAuth).containsExactly(null, "ok")
    }

    @Test
    fun `a blob whose bytes do not match its digest is refused`() {
        val blob = "the real archive".toByteArray()
        val descriptor = OciDescriptor(OciMediaTypes.ARCHIVE, OciDigest.sha256(blob), blob.size.toLong())
        val base = server { it.reply(200, "the fake archive".toByteArray()) }

        assertThatThrownBy { client(base).pullBlob("acme0/letters", descriptor, 1024) }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("digest")
    }

    @Test
    fun `a blob over the size limit is refused, from its descriptor and from what arrives`() {
        val blob = ByteArray(2048) { 1 }
        val base = server { it.reply(200, blob) }

        assertThatThrownBy { client(base).pullBlob("r/x", OciDescriptor(OciMediaTypes.ARCHIVE, OciDigest.sha256(blob), 2048), 1024) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.REGISTRY_ARCHIVE_REJECTED) }
        // A descriptor that understates the size: the cap holds while reading.
        assertThatThrownBy { client(base).pullBlob("r/x", OciDescriptor(OciMediaTypes.ARCHIVE, OciDigest.sha256(blob), 10), 1024) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.REGISTRY_ARCHIVE_REJECTED) }
    }

    @Test
    fun `a redirect to another host is followed without the registry's credentials`() {
        val blob = "archive".toByteArray()
        val storageAuth = CopyOnWriteArrayList<String>()
        val storage = server { exchange ->
            storageAuth += exchange.requestHeaders.getFirst("Authorization") ?: "none"
            exchange.reply(200, blob)
        }
        val base = server { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") == null) {
                exchange.reply(401, headers = mapOf("WWW-Authenticate" to """Basic realm="fake""""))
            } else {
                exchange.reply(307, headers = mapOf("Location" to "$storage/bucket/blob"))
            }
        }

        val pulled = client(base, "user", "pw").pullBlob("r/x", OciDescriptor(OciMediaTypes.ARCHIVE, OciDigest.sha256(blob), blob.size.toLong()), 1024)

        assertThat(pulled).isEqualTo(blob)
        assertThat(storageAuth).containsExactly("none")
    }

    @Test
    fun `a tag list is followed across pages, and a registry repeating a page does not loop`() {
        val base = server { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            when {
                "last=1.0.1" in query -> exchange.reply(200, """{"tags":["2.0.0"]}""".toByteArray(), mapOf("Link" to """</v2/r/x/tags/list?last=1.0.1&n=2>; rel="next""""))
                else -> exchange.reply(200, """{"tags":["1.0.0","1.0.1"]}""".toByteArray(), mapOf("Link" to """</v2/r/x/tags/list?last=1.0.1&n=2>; rel="next""""))
            }
        }

        assertThat(client(base).tags("r/x")).containsExactly("1.0.0", "1.0.1", "2.0.0")
    }

    @Test
    fun `a missing manifest is null, and a refusal without credentials says so`() {
        val missing = server { it.reply(404) }
        assertThat(client(missing).manifest("r/x", "1.0.0")).isNull()

        val locked = server { it.reply(401, headers = mapOf("WWW-Authenticate" to """Basic realm="fake"""")) }
        assertThatThrownBy { client(locked).manifest("r/x", "1.0.0") }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("no credentials are configured")
    }

    @Test
    fun `a manifest is read with its digest`() {
        val blob = OciDescriptor(OciMediaTypes.ARCHIVE, OciDigest.sha256("z".toByteArray()), 1)
        val bytes = manifestFor(blob)
        val base = server { exchange ->
            assertThat(exchange.requestHeaders.getFirst("Accept")).isEqualTo(OciMediaTypes.MANIFEST)
            exchange.reply(200, bytes)
        }

        val pulled = client(base).manifest("r/x", "1.0.0")!!
        assertThat(pulled.digest).isEqualTo(OciDigest.sha256(bytes))
        assertThat(pulled.manifest.archive).isEqualTo(blob)
    }

    @Test
    fun `a redirect to plaintext http is refused when plaintext is off`() {
        val base = server { it.reply(302, headers = mapOf("Location" to "http://example.invalid/blob")) }
        val https = OciRegistryClient(
            CatalogRegistryProperties.Registry(name = "fake", url = base),
            HttpClient.newHttpClient(),
            mapper,
            allowHttp = false,
            maxRedirects = 3,
        )
        assertThatThrownBy { https.manifest("r/x", "1.0.0") }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("plaintext http is off")
    }
}
