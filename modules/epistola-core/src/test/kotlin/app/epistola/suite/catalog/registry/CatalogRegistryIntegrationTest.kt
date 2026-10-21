// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CatalogImportValidationException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.catalog.sources.InstallCatalogRelease
import app.epistola.suite.catalog.sources.ListAvailableCatalogReleases
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SharedRegistries
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving catalog releases through a container registry (ADR 0032, section 7) against real CNCF
 * distribution registries (`registry:2`). Two tenants play two installations: A releases and
 * publishes, B installs.
 */
class CatalogRegistryIntegrationTest : IntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registries(registry: DynamicPropertyRegistry) {
            SharedRegistries.register(registry)
            registry.add("epistola.catalog.registries[2].name") { "tampering" }
            registry.add("epistola.catalog.registries[2].url") { tampering.url }
        }

        /** In front of the `local` registry: reads pass through, and every blob comes back with one byte changed. */
        private val tampering: TamperingProxy by lazy { TamperingProxy(SharedRegistries.url(SharedRegistries.local)) }
    }

    @Autowired private lateinit var registries: CatalogRegistries

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var probe: RegistryCatalogUpstreamProbe

    private data class Released(val a: TenantKey, val b: TenantKey, val catalog: CatalogKey, val namespace: String, val fingerprint: String)

    /** Tenant A with catalog [key] released as [version], holding theme `brand`; and an empty tenant B. */
    private fun released(key: String, version: String = "1.0.0"): Released {
        val a = createTenant("Registry $key A").id
        val b = createTenant("Registry $key B").id
        val catalog = CatalogKey.of(key)
        return withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Registry $key").execute()
            CreateTheme(id = theme(a, catalog), name = "Brand").execute()
            MarkCatalogReady(a, catalog).execute()
            val release = ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = version).execute()
            Released(a, b, catalog, GetCatalog(a, catalog).query()!!.namespace, release.fingerprint)
        }
    }

    private fun theme(tenant: TenantKey, catalog: CatalogKey) = ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant)))

    private fun releaseNext(r: Released, version: String, themeName: String): String = withMediator {
        UpdateTheme(theme(r.a, r.catalog), name = themeName).execute()
        MarkCatalogReady(r.a, r.catalog).execute()
        ReleaseCatalogVersion(tenantKey = r.a, catalogKey = r.catalog, version = version).execute().fingerprint
    }

    /** Pushes [archive] as `version` of [r]'s catalog, labelled with [fingerprint], as anyone with push rights could. */
    private fun pushRaw(r: Released, version: String, archive: ByteArray, fingerprint: String, catalogKey: String = r.catalog.value): String {
        val registry = registries.named("local")
        val client = registries.client(registry)
        val repository = registries.repository(registry, r.namespace, catalogKey)
        val labels = ReleaseLabels(version, fingerprint, "exchange.epistola.app", r.namespace, catalogKey)
        val config = client.pushBlob(repository, OciMediaTypes.CONFIG, ReleaseArtifact.config(mapper, labels))
        val layer = client.pushBlob(repository, OciMediaTypes.ARCHIVE, archive)
        return client.pushManifest(repository, version, ReleaseArtifact.manifest(mapper, labels, config, layer))
    }

    private fun export(r: Released, version: String = "1.0.0"): ByteArray = withMediator { ExportCatalogZip(r.a, r.catalog, version).execute().zipBytes }

    @Test
    fun `a release published from one installation installs in another with the same fingerprint`() {
        val r = released("reg-roundtrip")
        withMediator {
            val published = PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute()
            assertThat(published.alreadyPublished).isFalse()
            assertThat(published.repository).isEqualTo("${r.namespace}/reg-roundtrip")
            assertThat(published.digest).startsWith("sha256:")

            val listed = ListRegistryReleases(r.b, "local", r.namespace, "reg-roundtrip").query()
            assertThat(listed.conflict).isNull()
            assertThat(listed.releases.map { it.version }).containsExactly("1.0.0")
            assertThat(listed.releases.single().fingerprint).isEqualTo(r.fingerprint)
            assertThat(listed.releases.single().present).isFalse()

            val installed = InstallReleaseFromRegistry(r.b, "local", r.namespace, "reg-roundtrip", "1.0.0").execute()
            assertThat(installed.newlyInstalled).isTrue()
            assertThat(installed.digest).isEqualTo(published.digest)

            assertThat(ListCatalogReleases(r.b, r.catalog).query().single { it.version == "1.0.0" }.fingerprint)
                .`as`("the receiver has the release the publisher released")
                .isEqualTo(r.fingerprint)
            val catalog = GetCatalog(r.b, r.catalog).query()!!
            assertThat(catalog.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(catalog.sourceUrl).isEqualTo("oci:local/${r.namespace}/reg-roundtrip")
            assertThat(catalog.namespace).isEqualTo(r.namespace)

            assertThat(ListRegistryReleases(r.b, "local", r.namespace, "reg-roundtrip").query().releases.single().present).isTrue()
            assertThat(InstallReleaseFromRegistry(r.b, "local", r.namespace, "reg-roundtrip", "1.0.0").execute().newlyInstalled)
                .`as`("the same release again changes nothing")
                .isFalse()
        }
    }

    @Test
    fun `publishing a release the registry has already is a no-op`() {
        val r = released("reg-twice")
        withMediator {
            val first = PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute()
            val second = PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute()

            assertThat(second.alreadyPublished).isTrue()
            assertThat(second.digest).isEqualTo(first.digest)
        }
    }

    @Test
    fun `a number the registry holds with other content is refused and left as it is`() {
        val r = released("reg-taken")
        val other = releaseNext(r, "1.1.0", "Brand, changed")
        // Someone else's 1.0.0: the content of 1.1.0, under the number 1.0.0.
        val theirs = pushRaw(r, "1.0.0", export(r, "1.1.0"), other)

        withMediator {
            assertThatThrownBy { PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.REGISTRY_TAG_TAKEN) }
                .hasMessageContaining("One number names one release")
        }
        val registry = registries.named("local")
        assertThat(registries.client(registry).manifest(registries.repository(registry, r.namespace, "reg-taken"), "1.0.0")!!.digest)
            .`as`("not overwritten")
            .isEqualTo(theirs)
    }

    @Test
    fun `a tampered archive is refused on install whatever its labels say`() {
        val r = released("reg-tampered")
        // The content changed, its catalog.json and the manifest's label still claim the original fingerprint.
        pushRaw(r, "1.0.0", replaceInEntries(export(r), "Brand", "Bland"), r.fingerprint)

        withMediator {
            assertThatThrownBy { InstallReleaseFromRegistry(r.b, "local", r.namespace, "reg-tampered", "1.0.0").execute() }
                .isInstanceOf(CatalogImportValidationException::class.java)
            assertThat(GetCatalog(r.b, r.catalog).query()).isNull()
        }
    }

    @Test
    fun `a blob that does not match its digest is refused before anything is installed`() {
        val r = released("reg-altered")
        withMediator { PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute() }

        withMediator {
            // The same registry, through a proxy that alters every blob it serves by one byte.
            assertThatThrownBy { InstallReleaseFromRegistry(r.b, "tampering", r.namespace, "reg-altered", "1.0.0").execute() }
                .isInstanceOf(CatalogSourceUnavailableException::class.java)
                .hasMessageContaining("digest")
            assertThat(GetCatalog(r.b, r.catalog).query()).isNull()
        }
    }

    @Test
    fun `an archive of another catalog under this name is refused`() {
        val r = released("reg-named")
        pushRaw(r, "1.0.0", export(r), r.fingerprint, catalogKey = "reg-impostor")

        withMediator {
            assertThatThrownBy { InstallReleaseFromRegistry(r.b, "local", r.namespace, "reg-impostor", "1.0.0").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.REGISTRY_ARCHIVE_REJECTED) }
            assertThat(GetCatalog(r.b, CatalogKey.of("reg-named")).query()).isNull()
            assertThat(GetCatalog(r.b, CatalogKey.of("reg-impostor")).query()).isNull()
        }
    }

    @Test
    fun `a catalog key the receiver uses for another catalog is refused before downloading`() {
        val r = released("reg-clash")
        withMediator {
            PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute()
            CreateCatalog(tenantKey = r.b, id = r.catalog, name = "B's own").execute()

            assertThat(ListRegistryReleases(r.b, "local", r.namespace, "reg-clash").query().conflict).contains("another catalog")
            assertThatThrownBy { InstallReleaseFromRegistry(r.b, "local", r.namespace, "reg-clash", "1.0.0").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.REGISTRY_CATALOG_KEY_TAKEN) }
        }
    }

    @Test
    fun `a catalog installed from a registry is offered its later releases by that registry`() {
        val r = released("reg-later")
        withMediator {
            PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "local").execute()
            InstallReleaseFromRegistry(r.b, "local", r.namespace, "reg-later", "1.0.0").execute()
        }
        val later = releaseNext(r, "1.1.0", "Brand 1.1")
        withMediator {
            PublishReleaseToRegistry(r.a, r.catalog, "1.1.0", "local").execute()

            val catalog = GetCatalog(r.b, r.catalog).query()!!
            val state = probe.probe(catalog)
            assertThat(state.availableVersion).isEqualTo("1.1.0")
            assertThat(state.availableFingerprint).isEqualTo(later)

            assertThat(ListAvailableCatalogReleases(r.b, r.catalog).query().available.map { it.releaseVersion }).containsExactly("1.1.0")
            InstallCatalogRelease(r.b, r.catalog, "1.1.0").execute()
            assertThat(ListCatalogReleases(r.b, r.catalog).query().single { it.version == "1.1.0" }.fingerprint).isEqualTo(later)
        }
    }

    @Test
    fun `a registry behind Basic authentication is used with its credentials, and refuses without them`() {
        val r = released("reg-secured")
        withMediator {
            val published = PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "secured").execute()
            assertThat(published.repository).isEqualTo("${SharedRegistries.SECURED_PREFIX}/${r.namespace}/reg-secured")

            InstallReleaseFromRegistry(r.b, "secured", r.namespace, "reg-secured", "1.0.0").execute()
            assertThat(ListCatalogReleases(r.b, r.catalog).query().single().fingerprint).isEqualTo(r.fingerprint)
        }

        val anonymous = OciRegistryClient(
            CatalogRegistryProperties.Registry(name = "secured", url = SharedRegistries.url(SharedRegistries.secured)),
            HttpClient.newHttpClient(),
            mapper,
            allowHttp = true,
            maxRedirects = 3,
        )
        assertThatThrownBy { anonymous.tags(registries.repository(registries.named("secured"), r.namespace, "reg-secured")) }
            .isInstanceOf(CatalogSourceUnavailableException::class.java)
            .hasMessageContaining("no credentials are configured")
    }

    @Test
    fun `an unknown registry is refused by name`() {
        val r = released("reg-unknown")
        withMediator {
            assertThatThrownBy { PublishReleaseToRegistry(r.a, r.catalog, "1.0.0", "nowhere").execute() }
                .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.code).isEqualTo(ValidationCode.REGISTRY_UNKNOWN) }
            assertThat(ListCatalogRegistries(r.a).query().registries.map { it.name }).containsExactly("local", "secured", "tampering")
        }
    }

    /** [archive] with every entry but catalog.json holding [from] rewritten to hold [to]. */
    private fun replaceInEntries(archive: ByteArray, from: String, to: String): ByteArray {
        val out = ByteArrayOutputStream()
        var replaced = 0
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            ZipOutputStream(out).use { output ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    var bytes = input.readAllBytes()
                    if (entry.name != "catalog.json") {
                        val text = bytes.toString(Charsets.UTF_8)
                        if (text.contains(from)) {
                            bytes = text.replace(from, to).toByteArray(Charsets.UTF_8)
                            replaced++
                        }
                    }
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        check(replaced > 0) { "nothing to tamper with" }
        return out.toByteArray()
    }
}

/** A read-only HTTP proxy to [upstream] that flips one byte of every blob it serves. */
private class TamperingProxy(private val upstream: String) {
    private val client: HttpClient = HttpClient.newHttpClient()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val request = HttpRequest.newBuilder(URI(upstream + exchange.requestURI.toString()))
                    .method(exchange.requestMethod, HttpRequest.BodyPublishers.noBody())
                    .apply { exchange.requestHeaders.getFirst("Accept")?.let { header("Accept", it) } }
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                var body = response.body()
                if ("/blobs/" in exchange.requestURI.path && response.statusCode() == 200 && body.isNotEmpty()) {
                    body = body.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                }
                response.headers().map().forEach { (name, values) ->
                    if (!name.equals("content-length", true) && !name.equals("transfer-encoding", true) && !name.startsWith(":")) {
                        exchange.responseHeaders[name] = values
                    }
                }
                val head = exchange.requestMethod == "HEAD"
                exchange.sendResponseHeaders(response.statusCode(), if (head || body.isEmpty()) -1 else body.size.toLong())
                if (!head && body.isNotEmpty()) exchange.responseBody.write(body)
            }
        }
        start()
    }

    val url: String get() = "http://${server.address.hostString}:${server.address.port}"
}
