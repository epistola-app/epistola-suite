// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.themes.commands.CreateTheme
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `importCatalog` over REST: what it installs, and the contract's problem type for each refusal. */
@Import(
    TestcontainersConfiguration::class,
    UnloggedTablesTestConfiguration::class,
    CollectSmokeSecurityConfig::class,
)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "epistola.demo.enabled=false",
        "epistola.generation.polling.enabled=false",
    ],
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class ImportCatalogApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `a number or an id the tenant has as another release is release-number-taken`() {
        val (a, _) = seedTenantAndKey()
        val (b, keyB) = seedTenantAndKey()
        val (c, _) = seedTenantAndKey()
        val catalog = "imp-${UUID.randomUUID().toString().take(8)}"
        val ours = release(a, catalog, "Brand")
        val namespace = withMediator { GetCatalog(a, CatalogKey.of(catalog)).query()!!.namespace }
        // Another 1.0.0 of the same catalog, with other content.
        val theirs = release(c, catalog, "Another brand", namespace)

        assertThat(import(b, keyB, ours).statusCode).isEqualTo(HttpStatus.OK)
        assertProblem(import(b, keyB, theirs), HttpStatus.CONFLICT, "release-number-taken")

        // The same release under another number: its id names release 1.0.0 here.
        val renumbered = withManifest(ours) { (it.get("release") as ObjectNode).put("version", "1.1.0") }
        assertProblem(import(b, keyB, renumbered), HttpStatus.CONFLICT, "release-number-taken")
    }

    @Test
    fun `a release rendering with defaults this installation lacks names them in the problem`() {
        val (a, _) = seedTenantAndKey()
        val (b, keyB) = seedTenantAndKey()
        val archive = withManifest(release(a, "imp-${UUID.randomUUID().toString().take(8)}", "Brand")) { manifest ->
            ((manifest.get("release") as ObjectNode).get("metadata").get("app.epistola.suite") as ObjectNode).put("renderingDefaultsVersion", 999)
        }

        val response = import(b, keyB, archive)

        assertProblem(response, HttpStatus.BAD_REQUEST, "catalog-rendering-defaults-too-new")
        assertThat(JsonPath.read<String>(response.body!!, "$.renderingDefaultsVersion")).isEqualTo("999")
        assertThat(JsonPath.read<Int>(response.body!!, "$.supportedVersion")).isEqualTo(app.epistola.generation.pdf.RenderingDefaults.CURRENT.version)
    }

    @Test
    fun `an archive from before wire v8 is imported under the registry and namespace named, and refused without them`() {
        val (b, keyB) = seedTenantAndKey()
        val catalog = "imp-${UUID.randomUUID().toString().take(8)}"
        val archive = v7Archive(catalog)

        assertProblem(import(b, keyB, archive), HttpStatus.BAD_REQUEST, "bad-request")
        assertProblem(import(b, keyB, archive, "namespace" to "named-here"), HttpStatus.BAD_REQUEST, "bad-request")

        val imported = import(b, keyB, archive, "registry" to "~", "namespace" to "named-here")
        assertThat(imported.statusCode).`as`(imported.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(imported.body!!, "$.namespace")).isEqualTo("named-here")
        assertThat(JsonPath.read<String>(imported.body!!, "$.registry")).isEqualTo("exchange.epistola.app")
        assertThat(withMediator { GetCatalog(b, CatalogKey.of(catalog)).query()!!.namespace }).isEqualTo("named-here")
    }

    @Test
    fun `a wire v8 archive keeps its name, and another registry or namespace is refused`() {
        val (a, _) = seedTenantAndKey()
        val (b, keyB) = seedTenantAndKey()
        val catalog = "imp-${UUID.randomUUID().toString().take(8)}"
        val archive = release(a, catalog, "Brand", namespace = "v8-own-ns")

        assertProblem(import(b, keyB, archive, "namespace" to "another-ns"), HttpStatus.BAD_REQUEST, "bad-request")
        assertProblem(import(b, keyB, archive, "registry" to "registry.elsewhere.example"), HttpStatus.BAD_REQUEST, "bad-request")
        assertThat(withMediator { GetCatalog(b, CatalogKey.of(catalog)).query() }).isNull()

        val imported = import(b, keyB, archive, "registry" to "~", "namespace" to "v8-own-ns")
        assertThat(imported.statusCode).`as`(imported.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(imported.body!!, "$.namespace")).isEqualTo("v8-own-ns")
    }

    /** A wire v7 archive of [catalog], with no resources: it names no registry or namespace. */
    private fun v7Archive(catalog: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("catalog.json"))
            zip.write("""{"schemaVersion":7,"catalog":{"slug":"$catalog","name":"Old"},"publisher":{"name":"Test"},"release":{"version":"1.0.0"},"resources":[]}""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun release(tenant: TenantKey, catalog: String, theme: String, namespace: String? = null): ByteArray = withMediator {
        val key = CatalogKey.of(catalog)
        CreateCatalog(tenantKey = tenant, id = key, name = catalog, namespace = namespace).execute()
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant))), name = theme).execute()
        MarkCatalogReady(tenant, key).execute()
        ReleaseCatalogDraft(tenantKey = tenant, catalogKey = key, version = "1.0.0").execute()
        ExportCatalogZip(tenant, key, "1.0.0").execute().zipBytes
    }

    /** [archive] with its `catalog.json` changed by [edit]. */
    private fun withManifest(archive: ByteArray, edit: (ObjectNode) -> Unit): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entries[it.name] = zip.readAllBytes() }
        }
        val manifest = objectMapper.readTree(entries.getValue("catalog.json")) as ObjectNode
        edit(manifest)
        entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun import(tenant: TenantKey, apiKey: String, archive: ByteArray, vararg params: Pair<String, String>): ResponseEntity<String> {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add(
                "file",
                object : ByteArrayResource(archive) {
                    override fun getFilename() = "catalog.zip"
                },
            )
        }
        val query = params.joinToString("&") { (k, v) -> "$k=$v" }.let { if (it.isEmpty()) "" else "?$it" }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"), MediaType.APPLICATION_PROBLEM_JSON)
            set(HttpHeaders.USER_AGENT, "epistola-contract/0.5.0 import-catalog-it")
            set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
            set("Authorization", "ApiKey $apiKey")
        }
        return restTemplate.exchange("/api/tenants/${tenant.value}/catalogs/import$query", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    private fun assertProblem(response: ResponseEntity<String>, status: HttpStatus, slug: String) {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(status)
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/$slug")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("imp-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Import Catalog Tenant").execute()
        tenantKey to CreateApiKey(tenantId = tenantKey, name = "import-catalog-it").execute().plaintextKey
    }
}
