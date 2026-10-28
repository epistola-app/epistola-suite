// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SharedRegistries
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Publishing a release to a container registry that fails part of the way: the manifest refused,
 * the connection dropped during a blob upload, push refused while pull is allowed. Publishing
 * writes nothing to the tenant, and a registry keeps no tag until its manifest is stored, so each
 * failure leaves the release unpublished and a later publish completes it, reusing what was uploaded.
 */
class RegistryPublishFailureTest : IntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registries(registry: DynamicPropertyRegistry) {
            SharedRegistries.register(registry)
            registry.add("epistola.catalog.registries[2].name") { "faulty" }
            registry.add("epistola.catalog.registries[2].url") { proxy.url }
        }

        /** In front of the `local` registry; what reaches `local` through it is published there. */
        private val proxy: FaultyRegistryProxy by lazy { FaultyRegistryProxy(SharedRegistries.url(SharedRegistries.local)) }
    }

    private data class Released(val tenant: TenantKey, val catalog: CatalogKey, val namespace: String, val fingerprint: String)

    private fun released(key: String): Released {
        val tenant = createTenant("Registry fault $key").id
        val catalog = CatalogKey.of(key)
        return withMediator {
            CreateCatalog(tenantKey = tenant, id = catalog, name = "Registry fault $key").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(tenant, catalog)).execute()
            val release = ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.0.0").execute()
            Released(tenant, catalog, GetCatalog(testCatalog(tenant, catalog)).query()!!.namespace, release.fingerprint)
        }
    }

    private fun publish(r: Released) = withMediator { PublishReleaseToRegistry(testCatalog(r.tenant, r.catalog), "1.0.0", "faulty").execute() }

    /** The versions `local` holds of [r]'s catalog, read past the proxy. */
    private fun tagged(r: Released): List<String> = withMediator { ListRegistryReleases(r.tenant, "local", r.namespace, r.catalog.value).query().releases.map { it.version } }

    /** The tenant's side: the release is there as it was released, and nothing else changed. */
    private fun assertTenantUnchanged(r: Released) {
        val releases = withMediator { ListCatalogReleaseSummaries(testCatalog(r.tenant, r.catalog)).query() }
        assertThat(releases.map { it.version to it.fingerprint }).containsExactly("1.0.0" to r.fingerprint)
    }

    @Test
    fun `a refused manifest leaves no tag, and publishing again completes it without uploading the blobs again`() {
        val r = released("reg-fault-manifest")
        proxy.fail = { method, path -> if (method == "PUT" && "/manifests/" in path) 500 else null }

        assertThatThrownBy { publish(r) }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("answered 500 while storing")
        assertThat(tagged(r)).`as`("the blobs arrived, but no tag names them").isEmpty()
        assertTenantUnchanged(r)

        proxy.fail = { _, _ -> null }
        proxy.requests.clear()
        val published = publish(r)

        assertThat(published.alreadyPublished).isFalse()
        assertThat(tagged(r)).containsExactly("1.0.0")
        assertThat(proxy.requests.filter { it.startsWith("PUT ") && "/blobs/uploads/" in it }).`as`("blobs the registry has are not sent again").isEmpty()
    }

    @Test
    fun `a connection dropped during a blob upload leaves no tag, and publishing again completes it`() {
        val r = released("reg-fault-drop")
        proxy.fail = { method, path -> if (method == "PUT" && "/blobs/uploads/" in path) DROP else null }

        assertThatThrownBy { publish(r) }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("could not be reached")
        assertThat(tagged(r)).isEmpty()
        assertTenantUnchanged(r)

        proxy.fail = { _, _ -> null }
        assertThat(publish(r).alreadyPublished).isFalse()
        assertThat(tagged(r)).containsExactly("1.0.0")
    }

    @Test
    fun `a registry that refuses the push is reported as refusing, and nothing is tagged`() {
        val r = released("reg-fault-denied")
        proxy.fail = { method, _ -> if (method == "POST" || method == "PUT") 403 else null }

        assertThatThrownBy { publish(r) }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("refused it (403)")
        assertThat(tagged(r)).isEmpty()
        assertTenantUnchanged(r)
        proxy.fail = { _, _ -> null }
    }
}

/** A status that closes the connection without answering. */
private const val DROP = -1

/**
 * A forwarding proxy for an OCI registry that answers chosen requests itself: [fail] returns the
 * status to answer with ([DROP] to close the connection), or null to forward. Upload locations
 * point back at the proxy, so blob uploads pass through it too. Every request is in [requests].
 */
private class FaultyRegistryProxy(private val upstream: String) {
    @Volatile var fail: (method: String, path: String) -> Int? = { _, _ -> null }
    val requests = CopyOnWriteArrayList<String>()

    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val url: String get() = "http://${server.address.hostString}:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.toString()
        requests += "${exchange.requestMethod} $path"
        val body = exchange.requestBody.readAllBytes()
        when (val status = fail(exchange.requestMethod, path)) {
            DROP -> {
                exchange.close()
                return
            }

            null -> forward(exchange, path, body)

            else -> exchange.use { it.sendResponseHeaders(status, -1) }
        }
    }

    private fun forward(exchange: HttpExchange, path: String, body: ByteArray) = exchange.use {
        val request = HttpRequest.newBuilder(URI(upstream + path))
            .method(exchange.requestMethod, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .apply {
                listOf("Accept", "Content-Type", "Authorization").forEach { name -> exchange.requestHeaders.getFirst(name)?.let { header(name, it) } }
            }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        response.headers().map().forEach { (name, values) ->
            if (!name.equals("content-length", true) && !name.equals("transfer-encoding", true) && !name.startsWith(":")) {
                exchange.responseHeaders[name] = if (name.equals("location", true)) values.map { it.replace(upstream, url) } else values
            }
        }
        val answer = response.body()
        val head = exchange.requestMethod == "HEAD"
        exchange.sendResponseHeaders(response.statusCode(), if (head || answer.isEmpty()) -1 else answer.size.toLong())
        if (!head && answer.isNotEmpty()) exchange.responseBody.write(answer)
    }
}
