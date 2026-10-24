// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The contract's API follow-ups of ADR 0034 over HTTP: a release carries its content hash and is found
 * by it; a release number may be a patch release wherever a release is named; another catalog is named
 * by the alias its declaration gives it; a draft's changes include its catalog-level content, and a
 * draft that changed nothing has nothing to release.
 *
 * Tenant: catalog `shared` (theme `brand`, code list `colours`) released 1.0.0; catalog `letters`
 * (template `invoice`) released 1.0.0, then with a theme added 1.1.0; an environment `production`.
 */
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
class ContentAddressedReleasesApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    private class Seeded(val tenant: TenantKey, val apiKey: String)

    private fun seeded(): Seeded = withMediator {
        val tenant = TenantKey.of("cah-${UUID.randomUUID().toString().take(8)}")
        TestNamespaces.createTenant(tenant, "Content hash tenant").execute()
        CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production").execute()
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), shared)), "Brand").execute()
        CreateCodeList(CodeListId(CodeListKey.of("colours"), testCatalog(TenantId(tenant), shared)), "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"))).execute()
        MarkCatalogReady(testCatalog(tenant, shared)).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0").execute()

        CreateCatalog(tenant, letters, "Letters").execute()
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters)))
        CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
        mediator.publishAndRelease(variant)
        CreateTheme(ThemeId(ThemeKey.of("added"), testCatalog(TenantId(tenant), letters)), "Added").execute()
        mediator.publishAndRelease(app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))))
        Seeded(tenant, CreateApiKey(tenantId = tenant, name = "cah-it").execute().plaintextKey)
    }

    private fun path(seeded: Seeded, catalog: CatalogKey = letters) = withMediator { CatalogApiPaths.catalog(seeded.tenant, catalog.value) }

    // ---- 1. Content hash ----

    @Test
    fun `a release carries its content hash, is found by it, and the catalog names its highest release's`() {
        val seeded = seeded()
        val catalog = path(seeded)

        val list = get("$catalog/releases", seeded)
        val versions = JsonPath.read<List<String?>>(list, "$.items[*].version")
        val hashes = JsonPath.read<List<String?>>(list, "$.items[*].contentHash")
        assertThat(versions).containsExactly(null, "1.1.0", "1.0.0")
        assertThat(hashes[0]).`as`("a draft has none").isNull()
        assertThat(hashes.drop(1)).allSatisfy { assertThat(it).matches("v6:[0-9a-f]{64}") }
        assertThat(hashes.drop(1)).`as`("made under V6: the fingerprint").isEqualTo(JsonPath.read<List<String?>>(list, "$.items[*].fingerprint").drop(1))
        val v100 = hashes[2]!!

        assertThat(JsonPath.read<String>(get("$catalog/releases/1.0.0", seeded), "$.contentHash")).isEqualTo(v100)

        val found = get("$catalog/releases?contentHash=$v100", seeded)
        assertThat(JsonPath.read<List<String>>(found, "$.items[*].version")).containsExactly("1.0.0")
        assertThat(JsonPath.read<List<Any>>(get("$catalog/releases?contentHash=v6:${"0".repeat(64)}", seeded), "$.items")).`as`("none: an empty list").isEmpty()
        val malformed = exchange(HttpMethod.GET, "$catalog/releases?contentHash=v5:${"0".repeat(64)}", seeded)
        assertThat(malformed.statusCode).`as`("anything but a content hash").isEqualTo(HttpStatus.BAD_REQUEST)

        // Two numbers for one content: 1.1.1 re-releases 1.1.0 with new notes only. Paged as found.
        withMediator { ReleaseCatalogDraft(testCatalog(seeded.tenant, letters), "1.1.1", notes = "Notes only").execute() }
        val firstPage = get("$catalog/releases?contentHash=${hashes[1]}&size=1", seeded)
        assertThat(JsonPath.read<List<String>>(firstPage, "$.items[*].version")).containsExactly("1.1.1")
        assertThat(JsonPath.read<Int>(firstPage, "$.page.total")).isEqualTo(2)
        assertThat(JsonPath.read<List<String>>(get("$catalog/releases?contentHash=${hashes[1]}&size=1&page=1", seeded), "$.items[*].version")).containsExactly("1.1.0")
        assertThat(JsonPath.read<List<Any>>(get("$catalog/releases?contentHash=${hashes[1]}&status=draft", seeded), "$.items")).`as`("a draft has no content hash").isEmpty()

        val catalogs = get("/api/tenants/${seeded.tenant.value}/catalogs", seeded)
        assertThat(JsonPath.read<List<String?>>(catalogs, "$.items[?(@.catalogKey == 'letters')].contentHash")).`as`("its highest release, 1.1.1").containsExactly(hashes[1])
    }

    @Test
    fun `releasing over HTTP returns the release with its content hash`() {
        val seeded = seeded()
        val catalog = path(seeded)
        withMediator { CreateTheme(ThemeId(ThemeKey.of("third"), testCatalog(TenantId(seeded.tenant), letters)), "Third").execute() }
        exchange(HttpMethod.POST, "$catalog/releases/next/mark-ready", seeded)

        val released = exchange(HttpMethod.POST, "$catalog/releases/next/release", seeded, "{}")

        assertThat(released.statusCode).`as`(released.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(released.body!!, "$.release.contentHash")).matches("v6:[0-9a-f]{64}").isEqualTo(JsonPath.read<String>(released.body!!, "$.release.fingerprint"))
    }

    @Test
    fun `releasing over HTTP in a namespace the tenant does not hold is refused, saying so`() {
        val seeded = seeded()
        val receiver = withMediator {
            val tenant = TenantKey.of("cah-${UUID.randomUUID().toString().take(8)}")
            TestNamespaces.createTenant(tenant, "Content hash receiver").execute()
            // Receiving needs no grant.
            ImportCatalogZip(tenantKey = tenant, zipBytes = ExportCatalogZip(testCatalog(seeded.tenant, letters), "1.1.0").execute().zipBytes).execute()
            CreateTheme(ThemeId(ThemeKey.of("ours"), testCatalog(TenantId(tenant), letters)), "Ours").execute()
            Seeded(tenant, CreateApiKey(tenantId = tenant, name = "cah-receiver").execute().plaintextKey)
        }
        val catalog = path(receiver)
        exchange(HttpMethod.POST, "$catalog/releases/next/mark-ready", receiver)

        val refused = exchange(HttpMethod.POST, "$catalog/releases/next/release", receiver, "{}")

        assertThat(refused.statusCode).`as`(refused.body).isEqualTo(HttpStatus.BAD_REQUEST)
        val namespace = TestNamespaces.of(seeded.tenant)
        assertThat(JsonPath.read<String>(refused.body!!, "$.detail"))
            .isEqualTo("This tenant does not hold the namespace ${namespace.namespace} (${namespace.authority}); an administrator can grant it.")
        assertThat(JsonPath.read<String>(refused.body!!, "$.type")).endsWith("/catalog-namespace-not-held")
    }

    @Test
    fun `two catalogs with one key are each reached by the path that names their namespace`() {
        val (tenant, apiKey) = withMediator {
            val tenant = TenantKey.of("cah-${UUID.randomUUID().toString().take(8)}")
            app.epistola.suite.tenants.commands.CreateTenant(tenant, "Two letters", listOf(app.epistola.suite.catalog.namespaces.NamespaceName("gemeente-x.nl", "acme"), app.epistola.suite.catalog.namespaces.NamespaceName("gemeente-x.nl", "bureau"))).execute()
            for (namespace in listOf("acme", "bureau")) {
                CreateCatalog(tenant, letters, "Letters of $namespace", namespace = namespace).execute()
                CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(namespace, letters, TenantId(tenant))), "Invoice of $namespace").execute()
            }
            tenant to CreateApiKey(tenantId = tenant, name = "two-letters").execute().plaintextKey
        }
        val seeded = Seeded(tenant, apiKey)
        for (namespace in listOf("acme", "bureau")) {
            val templates = get("/api/tenants/${tenant.value}/catalogs/gemeente-x.nl/$namespace/letters/releases/next/templates", seeded)
            assertThat(JsonPath.read<List<String>>(templates, "$.items[*].name")).containsExactly("Invoice of $namespace")
        }
        // Listed as two catalogs, each saying it can be changed here.
        val catalogs = get("/api/tenants/${tenant.value}/catalogs?size=100", seeded)
        assertThat(JsonPath.read<List<String>>(catalogs, "$.items[?(@.catalogKey == 'letters')].namespace")).containsExactlyInAnyOrder("acme", "bureau")
        assertThat(JsonPath.read<List<Boolean>>(catalogs, "$.items[?(@.catalogKey == 'letters')].editable")).containsExactly(true, true)
        // Served side by side, each deployment named by its own catalog.
        withMediator {
            val production = app.epistola.suite.common.ids.EnvironmentId(app.epistola.suite.common.ids.EnvironmentKey.of("production"), TenantId(tenant))
            app.epistola.suite.environments.commands.CreateEnvironment(production, "Production").execute()
            for (namespace in listOf("acme", "bureau")) {
                val catalog = CatalogId(namespace, letters, TenantId(tenant))
                app.epistola.suite.environments.commands.DeployCatalogRelease(production, catalog, mediator.publishAndRelease(app.epistola.suite.common.ids.VariantId(app.epistola.suite.common.ids.VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog)))).execute()
            }
        }
        val deployments = get("/api/tenants/${tenant.value}/environments/production/deployments", seeded)
        assertThat(JsonPath.read<List<String>>(deployments, "$.items[?(@.catalogKey == 'letters')].namespace")).containsExactlyInAnyOrder("acme", "bureau")
    }

    // ---- 2. Patch release numbers ----

    @Test
    fun `a patch release is named in paths and queries, and releasing one here is refused`() {
        val seeded = seeded()
        val catalog = path(seeded)
        val patch = "1.1.1-municipality.1"
        withMediator {
            val archive = ExportCatalogZip(testCatalog(seeded.tenant, letters), "1.1.0").execute().zipBytes
            ImportCatalogZip(tenantKey = seeded.tenant, zipBytes = numbered(archive, patch)).execute()
        }

        val one = exchange(HttpMethod.GET, "$catalog/releases/$patch", seeded)
        assertThat(one.statusCode).`as`(one.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(one.body!!, "$.version")).isEqualTo(patch)
        assertThat(exchange(HttpMethod.GET, "$catalog/releases/$patch/themes", seeded).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(exchange(HttpMethod.GET, "$catalog/releases/next/merge-preview?version=$patch", seeded).statusCode).`as`("in a query").isEqualTo(HttpStatus.OK)
        val namespace = withMediator { CatalogApiPaths.namespace(seeded.tenant, letters.value) }
        val deployed = exchange(HttpMethod.PUT, "/api/tenants/${seeded.tenant.value}/environments/production/deployments/~/$namespace/letters/$patch", seeded)
        assertThat(deployed.statusCode).`as`(deployed.body).isEqualTo(HttpStatus.OK)

        withMediator { CreateTheme(ThemeId(ThemeKey.of("fix"), testCatalog(TenantId(seeded.tenant), letters)), "Fix").execute() }
        exchange(HttpMethod.POST, "$catalog/releases/next/mark-ready", seeded)
        // Above what the change needs (1.2.0), so only its being a patch number refuses it.
        val issued = exchange(HttpMethod.POST, "$catalog/releases/next/release", seeded, """{"version":"1.3.0-municipality.1"}""")
        assertThat(issued.statusCode).`as`("issuing a patch number is not offered yet").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(issued.body).contains("patch release")
    }

    @Test
    fun `a version range may be up to 256 characters`() {
        val seeded = seeded()
        val namespace = withMediator { CatalogApiPaths.namespace(seeded.tenant, letters.value) }
        val range = "1.0.0 || ".repeat(25) + "1.1.0"
        assertThat(range.length).isGreaterThan(100).isLessThanOrEqualTo(256)
        val body = """{"namespace":"$namespace","catalogKey":"letters","templateKey":"invoice","variantKey":"${VariantKey.INITIAL.value}","versionRange":"$range","data":{}}"""

        val accepted = exchange(HttpMethod.POST, "/api/tenants/${seeded.tenant.value}/documents/generate", seeded, body)

        assertThat(accepted.statusCode).`as`(accepted.body).isEqualTo(HttpStatus.ACCEPTED)
    }

    // ---- 3. References by alias ----

    @Test
    fun `dependencies name their alias and the pinned release's content hash, and another alias is refused`() {
        val seeded = seeded()
        val catalog = path(seeded)
        val sharedNamespace = withMediator { CatalogApiPaths.namespace(seeded.tenant, shared.value) }
        val sharedHash = JsonPath.read<String>(get("${path(seeded, shared)}/releases/1.0.0", seeded), "$.contentHash")

        val declared = exchange(HttpMethod.PUT, "$catalog/releases/next/dependencies", seeded, """{"dependencies":[{"namespace":"$sharedNamespace","catalogKey":"shared","version":"1.0.0"}]}""")
        assertThat(declared.statusCode).`as`(declared.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(declared.body!!, "$.items[0].alias")).`as`("by default the catalog's key").isEqualTo("shared")
        assertThat(JsonPath.read<String>(declared.body!!, "$.items[0].contentHash")).isEqualTo(sharedHash)

        val same = exchange(HttpMethod.PUT, "$catalog/releases/next/dependencies", seeded, """{"dependencies":[{"alias":"shared","namespace":"$sharedNamespace","catalogKey":"shared","version":"1.0.0"}]}""")
        assertThat(same.statusCode).isEqualTo(HttpStatus.OK)
        val other = exchange(HttpMethod.PUT, "$catalog/releases/next/dependencies", seeded, """{"dependencies":[{"alias":"brand","namespace":"$sharedNamespace","catalogKey":"shared","version":"1.0.0"}]}""")
        assertThat(other.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(JsonPath.read<List<String>>(other.body!!, "$.errors[*].field")).containsExactly("dependencies[0].alias")
        assertThat(other.body).contains("by that catalog's key")

        exchange(HttpMethod.POST, "$catalog/releases/next/mark-ready", seeded)
        val released = exchange(HttpMethod.POST, "$catalog/releases/next/release", seeded, "{}")
        assertThat(released.statusCode).`as`(released.body).isEqualTo(HttpStatus.OK)
        val pins = JsonPath.read<List<Map<String, Any?>>>(released.body!!, "$.release.dependencies")
        assertThat(pins.single()).containsEntry("alias", "shared").containsEntry("catalogKey", "shared").containsEntry("contentHash", sharedHash)
    }

    @Test
    fun `another catalog's theme and code list are named by alias, and an alias the draft does not declare is refused`() {
        val seeded = seeded()
        val catalog = path(seeded)
        val sharedNamespace = withMediator { CatalogApiPaths.namespace(seeded.tenant, shared.value) }

        val undeclared = exchange(HttpMethod.POST, "$catalog/releases/next/attributes", seeded, """{"key":"colour","displayName":"Colour","codeListBinding":{"catalogKey":"shared","key":"colours"}}""")
        assertThat(undeclared.statusCode).`as`(undeclared.body).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(JsonPath.read<List<String>>(undeclared.body!!, "$.errors[*].field")).containsExactly("codeListBinding.catalogKey")

        exchange(HttpMethod.PUT, "$catalog/releases/next/dependencies", seeded, """{"dependencies":[{"namespace":"$sharedNamespace","catalogKey":"shared","version":"1.0.0"}]}""")
        val bound = exchange(HttpMethod.POST, "$catalog/releases/next/attributes", seeded, """{"key":"colour","displayName":"Colour","codeListBinding":{"catalogKey":"shared","key":"colours"}}""")
        assertThat(bound.statusCode).`as`(bound.body).isEqualTo(HttpStatus.CREATED)
        assertThat(JsonPath.read<Map<String, Any?>>(bound.body!!, "$.codeListBinding")).isEqualTo(mapOf("catalogKey" to "shared", "key" to "colours"))

        val themed = exchange(HttpMethod.PATCH, "$catalog/releases/next", seeded, """{"defaultTheme":{"key":"brand","catalogKey":"shared"}}""")
        assertThat(themed.statusCode).`as`(themed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<Map<String, Any?>>(get("$catalog/releases/next", seeded), "$.defaultTheme")).isEqualTo(mapOf("key" to "brand", "catalogKey" to "shared"))
    }

    // ---- 4. A draft's changes ----

    @Test
    fun `a draft's changes list its catalog-level content, and a draft with none is not releasable`() {
        val seeded = seeded()
        val catalog = path(seeded)
        val sharedNamespace = withMediator { CatalogApiPaths.namespace(seeded.tenant, shared.value) }

        val unchanged = get("$catalog/releases/next/changes", seeded)
        assertThat(JsonPath.read<Boolean>(unchanged, "$.releasable")).`as`("nothing changed against 1.1.0").isFalse()
        assertThat(JsonPath.read<List<Any>>(unchanged, "$.changes")).isEmpty()
        assertThat(JsonPath.read<List<Any>>(unchanged, "$.catalogChanges")).isEmpty()
        val refused = exchange(HttpMethod.POST, "$catalog/releases/next/release", seeded, "{}")
        assertThat(refused.statusCode).`as`(refused.body).isEqualTo(HttpStatus.CONFLICT)
        assertThat(JsonPath.read<String>(refused.body!!, "$.type")).isEqualTo("https://epistola.app/errors/conflict")

        exchange(HttpMethod.PUT, "$catalog/releases/next/dependencies", seeded, """{"dependencies":[{"namespace":"$sharedNamespace","catalogKey":"shared","version":"1.0.0"}]}""")
        exchange(HttpMethod.PATCH, "$catalog/releases/next", seeded, """{"defaultTheme":{"key":"brand","catalogKey":"shared"}}""")
        val changed = get("$catalog/releases/next/changes", seeded)
        assertThat(JsonPath.read<List<Map<String, Any?>>>(changed, "$.catalogChanges")).containsExactlyInAnyOrder(
            mapOf("type" to "catalogDependency", "key" to "shared", "change" to "added"),
            mapOf("type" to "catalog", "key" to "defaultTheme", "change" to "added"),
        )
        assertThat(JsonPath.read<Boolean>(changed, "$.dependenciesChanged")).isTrue()
        assertThat(JsonPath.read<Boolean>(changed, "$.releasable")).`as`("a catalog-level change is a change; it needs no ready mark").isTrue()

        withMediator { CreateTheme(ThemeId(ThemeKey.of("third"), testCatalog(TenantId(seeded.tenant), letters)), "Third").execute() }
        val withResource = get("$catalog/releases/next/changes", seeded)
        assertThat(JsonPath.read<List<Boolean>>(withResource, "$.changes[?(@.key == 'third')].mergedForReview")).containsExactly(false)
        assertThat(JsonPath.read<Boolean>(withResource, "$.releasable")).`as`("a resource not marked ready").isFalse()

        val released = exchange(HttpMethod.POST, "$catalog/releases/next/mark-ready", seeded).let { exchange(HttpMethod.POST, "$catalog/releases/next/release", seeded, "{}") }
        assertThat(released.statusCode).`as`(released.body).isEqualTo(HttpStatus.OK)
    }

    // ---- helpers ----

    /** [zip] numbered [version], with no release id of its own, as another issuer exports it. */
    private fun numbered(zip: ByteArray, version: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { writer ->
            ZipInputStream(ByteArrayInputStream(zip)).use { reader ->
                var entry = reader.nextEntry
                while (entry != null) {
                    var bytes = reader.readAllBytes()
                    if (entry.name == "catalog.json") {
                        val manifest = objectMapper.readTree(bytes) as ObjectNode
                        (manifest.get("release") as ObjectNode).put("version", version).remove("releaseId")
                        bytes = objectMapper.writeValueAsBytes(manifest)
                    }
                    writer.putNextEntry(ZipEntry(entry.name))
                    writer.write(bytes)
                    writer.closeEntry()
                    entry = reader.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    private fun get(path: String, seeded: Seeded): String {
        val response = exchange(HttpMethod.GET, path, seeded)
        assertThat(response.statusCode).`as`("$path: ${response.body}").isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    private fun exchange(method: HttpMethod, path: String, seeded: Seeded, body: String? = null): ResponseEntity<String> = restTemplate.exchange(path, method, HttpEntity(body, headers(seeded.apiKey)), String::class.java)

    private fun headers(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0-SNAPSHOT content-hash-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }
}
