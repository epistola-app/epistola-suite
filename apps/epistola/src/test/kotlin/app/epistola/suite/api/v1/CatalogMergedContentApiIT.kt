// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.merge.MergeBaseMissingException
import app.epistola.suite.catalog.merge.MergeEntryNotFoundException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.testing.parallelReleaseLines
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.util.AopTestUtils
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Resolving a merge conflict with merged content over REST (contract ADR 0005, alpha):
 * `getCatalogDraftMergeSides`, and `mergeIntoCatalogDraft` with `mergedContent`.
 *
 * The catalog's 1.0.0 has the theme `tha`; parallel work made from it renames the theme and adds a
 * template on it, released as 1.1.0, while the next release renames the theme differently.
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
class CatalogMergedContentApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private data class Seeded(val tenantKey: TenantKey, val apiKey: String, val catalogKey: CatalogKey, val next: String)

    private fun seeded(slug: String): Seeded {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogKey = CatalogKey.of(slug)
        val theme = ThemeId(ThemeKey.of("tha"), CatalogId(catalogKey, TenantId(tenantKey)))
        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Merged $slug").execute()
            app.epistola.suite.themes.commands.CreateTheme(id = theme, name = "Th").execute()
            MarkCatalogReady(tenantKey, catalogKey).execute()
            ReleaseCatalogDraft(tenantKey = tenantKey, catalogKey = catalogKey, version = "1.0.0").execute()
            val fixRef = CatalogReleaseRef(tenantKey, catalogKey, DraftRef.Id(CreateCatalogDraft(tenantKey, catalogKey, "Fix", "1.0.0").execute().versionId))
            UpdateTheme(theme, name = "Theirs", catalogVersion = fixRef).execute()
            val letter = TemplateId(TemplateKey.of("letter"), CatalogId(catalogKey, TenantId(tenantKey)))
            CreateDocumentTemplate(id = letter, name = "Letter", catalogVersion = fixRef).execute()
            UpdateDocumentTemplate(id = letter, themeId = ThemeKey.of("tha"), themeCatalogKey = catalogKey, catalogVersion = fixRef).execute()
            MarkCatalogReady(tenantKey, catalogKey, catalogVersion = fixRef).execute()
            ReleaseCatalogDraft(tenantKey = tenantKey, catalogKey = catalogKey, version = "1.1.0", catalogVersion = fixRef).execute()
            UpdateTheme(theme, name = "Ours").execute()
        }
        return Seeded(tenantKey, apiKey, catalogKey, withMediator { CatalogApiPaths.version(tenantKey, slug, "next") })
    }

    private fun get(seeded: Seeded, path: String) = restTemplate.exchange("${seeded.next}/$path", HttpMethod.GET, HttpEntity<Void>(baseHeaders(seeded.apiKey)), String::class.java)

    private fun merge(seeded: Seeded, body: Map<String, Any?>) = restTemplate.exchange("${seeded.next}/merge", HttpMethod.POST, HttpEntity(objectMapper.writeValueAsString(body), baseHeaders(seeded.apiKey)), String::class.java)

    private fun problem(response: ResponseEntity<String>, status: HttpStatus, type: String) {
        assertThat(response.statusCode).`as`(response.body).isEqualTo(status)
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/$type")
    }

    @Autowired
    private lateinit var catalogApi: EpistolaCatalogApi

    /**
     * `getCatalogDraftMergeSides`, called on the controller itself. The contract's generated
     * interface declares `entry`'s pattern as `^[A-Za-z][A-Za-z0-9-]*_/[^/]+$` (the generator writes
     * the `*` and `/` of the contract's pattern apart, as `*_/`), which no entry matches, so over HTTP
     * every entry is refused with 400 until the contract fixes it.
     */
    private fun sides(seeded: Seeded, version: String, entry: String) = withMediator {
        val target = AopTestUtils.getTargetObject<EpistolaCatalogApi>(catalogApi)
        val catalog = GetCatalog(seeded.tenantKey, seeded.catalogKey).query()!!
        target.getCatalogDraftMergeSides(version, entry, seeded.tenantKey.value, catalog.registry, catalog.namespace, seeded.catalogKey.value, "next").body!!
    }

    @Test
    fun `the preview says what each entry needs, and the sides of a conflict are read with the preview's token`() {
        val seeded = seeded("merged-sides-api")
        val preview = get(seeded, "merge-preview?version=1.1.0")
        assertThat(JsonPath.read<List<String>>(preview.body!!, "$.resources[?(@.resourceKey == 'letter')].needs[*]")).containsExactly("theme/tha")

        val sides = sides(seeded, "1.1.0", "theme/tha")

        assertThat(sides.entry).isEqualTo("theme/tha")
        assertThat(sides.mergeBase).isEqualTo("1.0.0")
        assertThat(sides.token).isEqualTo(JsonPath.read<String>(preview.body!!, "$.token"))
        assertThat(sides.base).containsEntry("name", "Th").containsEntry("type", "theme").containsEntry("key", "tha")
        assertThat(sides.draft).containsEntry("name", "Ours")
        assertThat(sides.release).containsEntry("name", "Theirs")

        val added = sides(seeded, "1.1.0", "template/letter")
        assertThat(added.base).isNull()
        assertThat(added.draft).isNull()
        assertThat(added.release).containsEntry("key", "letter")

        assertThatThrownBy { sides(seeded, "1.1.0", "theme/nothing") }.isInstanceOf(MergeEntryNotFoundException::class.java)
    }

    @Test
    fun `an entry that is not type and key is refused over HTTP, naming the parameter`() {
        val seeded = seeded("merged-sides-http-api")
        val malformed = get(seeded, "merge-sides?version=1.1.0&entry=nothing")
        problem(malformed, HttpStatus.BAD_REQUEST, "validation-error")
        assertThat(JsonPath.read<List<String>>(malformed.body!!, "$.errors[*].field")).containsExactly("entry")
    }

    @Test
    fun `the sides cannot be read without the merge base`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val producer = createTenant("Merged Sides Producer").id
        val slug = "merged-base-api"
        withMediator {
            val lines = mediator.parallelReleaseLines(producer, CatalogKey.of(slug))
            ImportCatalogZip(tenantKey = tenantKey, zipBytes = lines.v110).execute()
            ImportCatalogZip(tenantKey = tenantKey, zipBytes = lines.v101).execute()
        }
        val seeded = Seeded(tenantKey, apiKey, CatalogKey.of(slug), withMediator { CatalogApiPaths.version(tenantKey, slug, "next") })

        assertThatThrownBy { sides(seeded, "1.0.1", "theme/brand") }
            .isInstanceOfSatisfying(MergeBaseMissingException::class.java) { assertThat(it.version).isEqualTo("1.0.0") }
    }

    @Test
    fun `a conflict resolved to merged content is written as given and listed as merged`() {
        val seeded = seeded("merged-write-api")
        val sides = sides(seeded, "1.1.0", "theme/tha")
        val content = sides.draft!! + ("name" to "Ours and theirs")

        val merged = merge(seeded, mapOf("release" to "1.1.0", "mergedContent" to mapOf("theme/tha" to content), "expectedToken" to sides.token))

        assertThat(merged.statusCode).`as`(merged.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(merged.body!!, "$.merged")).containsExactly("theme/tha")
        assertThat(JsonPath.read<List<String>>(merged.body!!, "$.taken")).containsExactly("template/letter")
        assertThat(JsonPath.read<List<String>>(merged.body!!, "$.kept")).isEmpty()
        val theme = withMediator { GetTheme(ThemeId(ThemeKey.of("tha"), CatalogId(seeded.catalogKey, TenantId(seeded.tenantKey)))).query() }
        assertThat(theme?.name).isEqualTo("Ours and theirs")
    }

    @Test
    fun `refusals of merged content name the contract's problem types`() {
        val seeded = seeded("merged-refused-api")
        val draft = sides(seeded, "1.1.0", "theme/tha").draft!!
        val letter = sides(seeded, "1.1.0", "template/letter").release!!

        val invalid = merge(seeded, mapOf("release" to "1.1.0", "mergedContent" to mapOf("theme/tha" to draft + ("key" to "Tha!"))))
        problem(invalid, HttpStatus.BAD_REQUEST, "validation-error")
        assertThat(JsonPath.read<List<String>>(invalid.body!!, "$.errors[*].field")).containsExactly("mergedContent.theme/tha/key")

        val twice = merge(seeded, mapOf("release" to "1.1.0", "resolutions" to mapOf("theme/tha" to "ours"), "mergedContent" to mapOf("theme/tha" to draft)))
        problem(twice, HttpStatus.BAD_REQUEST, "validation-error")
        assertThat(JsonPath.read<List<String>>(twice.body!!, "$.errors[*].field")).containsExactly("mergedContent.theme/tha")

        val notConflict = merge(seeded, mapOf("release" to "1.1.0", "resolutions" to mapOf("theme/tha" to "ours"), "mergedContent" to mapOf("template/letter" to letter)))
        problem(notConflict, HttpStatus.UNPROCESSABLE_CONTENT, "merge-not-applicable")

        // Merged content that drops the theme's styles is fine; one that uses a font the result lacks is not.
        val unresolved = merge(seeded, mapOf("release" to "1.1.0", "mergedContent" to mapOf("theme/tha" to draft + ("documentStyles" to mapOf("fontFamily" to mapOf("key" to "ghost"))))))
        problem(unresolved, HttpStatus.CONFLICT, "merge-refused")
        assertThat(JsonPath.read<String>(unresolved.body!!, "$.detail")).contains("theme 'tha' uses font 'ghost', which this catalog does not have")
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/2.0.0 merged-content-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("Authorization", "ApiKey $apiKey")
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("mc-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Merged Content Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "mc-it").execute()
        tenantKey to created.plaintextKey
    }
}
