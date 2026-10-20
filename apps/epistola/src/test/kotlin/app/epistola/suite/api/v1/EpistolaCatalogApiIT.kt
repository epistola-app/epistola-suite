// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
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
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

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
class EpistolaCatalogApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `releasing the next version numbers it, keeps its id, and the next version continues on the release`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        val before = JsonPath.read<String>(
            restTemplate.exchange(versionPath(tenantKey, catalogSlug, "next"), HttpMethod.GET, HttpEntity<String>(baseHeaders(apiKey)), String::class.java).body!!,
            "$.versionId",
        )

        val response = restTemplate.exchange(
            "${versionPath(tenantKey, catalogSlug)}/release",
            HttpMethod.POST,
            HttpEntity("""{"notes":"first cut"}""", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.releaseVersion")).isEqualTo("1.0.0")
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.status")).isEqualTo("released")
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.fingerprint")).isNotBlank()
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.notes")).isEqualTo("first cut")
        assertThat(JsonPath.read<Boolean>(body, "$.unchangedContent")).isFalse
        assertThat(JsonPath.read<Map<String, Any>>(body, "$")).doesNotContainKey("openedDraft")
        assertThat(JsonPath.read<String>(body, "$.catalogVersion.versionId")).`as`("the release keeps the draft's id").isEqualTo(before)
        assertThat(JsonPath.read<List<Any>>(body, "$.releasedDependencies")).isEmpty()

        val byId = restTemplate.exchange(versionPath(tenantKey, catalogSlug, before), HttpMethod.GET, HttpEntity<String>(baseHeaders(apiKey)), String::class.java).body!!
        assertThat(JsonPath.read<String>(byId, "$.releaseVersion")).`as`("its id addresses the release").isEqualTo("1.0.0")
        val next = restTemplate.exchange(versionPath(tenantKey, catalogSlug, "next"), HttpMethod.GET, HttpEntity<String>(baseHeaders(apiKey)), String::class.java).body!!
        assertThat(JsonPath.read<String>(next, "$.name")).isEqualTo("Next version")
        assertThat(JsonPath.read<String>(next, "$.versionId")).isNotEqualTo(before)
        assertThat(JsonPath.read<String?>(next, "$.releaseVersion")).`as`("numbered only when released").isNull()
        assertThat(JsonPath.read<String>(next, "$.base")).isEqualTo("1.0.0")
    }

    @Test
    fun `release missing catalog returns catalog not found problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val namespace = withMediator { CatalogApiPaths.namespace(tenantKey) }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/~/$namespace/missing-catalog/versions/next/release",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/catalog-not-found")
        assertThat(JsonPath.read<String>(body, "$.catalogId")).isEqualTo("missing-catalog")
    }

    @Test
    fun `releasing a released version again is refused with actionable problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            ReleaseCatalogVersion(
                tenantKey = tenantKey,
                catalogKey = CatalogKey.of(catalogSlug),
                version = "1.0.0",
            ).execute()
        }

        val response = restTemplate.exchange(
            "${versionPath(tenantKey, catalogSlug, "1.0.0")}/release",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-released")
        assertThat(JsonPath.read<String>(body, "$.version")).isEqualTo("1.0.0")
    }

    @Test
    fun `the release preview gives the number the changes need, with the reasons, and a lower number is refused`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug), version = "1.0.0").execute()
            DeleteTheme(ThemeId(ThemeKey.of("tha"), CatalogId(CatalogKey.of(catalogSlug), TenantId(tenantKey)))).execute()
            MarkCatalogReady(tenantKey, CatalogKey.of(catalogSlug)).execute()
        }
        fun call(method: HttpMethod, path: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, baseHeaders(apiKey)), String::class.java)

        val preview = call(HttpMethod.GET, "${versionPath(tenantKey, catalogSlug, "next")}/release-preview")
        assertThat(preview.statusCode).describedAs(preview.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(preview.body!!, "$.base")).isEqualTo("1.0.0")
        assertThat(JsonPath.read<String>(preview.body!!, "$.needed")).isEqualTo("2.0.0")
        assertThat(JsonPath.read<String>(preview.body!!, "$.changeKind")).isEqualTo("breaking")
        assertThat(JsonPath.read<List<String>>(preview.body!!, "$.reasons")).contains("theme 'tha': removed")
        assertThat(JsonPath.read<String>(preview.body!!, "$.suggested")).isEqualTo("2.0.0")

        val refused = call(HttpMethod.POST, "${versionPath(tenantKey, catalogSlug, "next")}/release", """{"releaseVersion":"1.0.1"}""")
        assertThat(refused.statusCode.is4xxClientError).describedAs(refused.body).isTrue()
        assertThat(refused.body).contains("needs at least 2.0.0")

        val released = call(HttpMethod.POST, "${versionPath(tenantKey, catalogSlug, "next")}/release", "{}")
        assertThat(released.statusCode).describedAs(released.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(released.body!!, "$.catalogVersion.releaseVersion")).isEqualTo("2.0.0")
    }

    @Test
    fun `a released version names the base its draft started from`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "rel-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug), version = "1.0.0").execute()
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug)).execute()
        }

        val response = restTemplate.exchange(versionPath(tenantKey, catalogSlug, "1.0.1"), HttpMethod.GET, HttpEntity<String>(baseHeaders(apiKey)), String::class.java)

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(response.body!!, "$.status")).isEqualTo("released")
        assertThat(JsonPath.read<String>(response.body!!, "$.base")).isEqualTo("1.0.0")
    }

    /**
     * A draft (contract ADR 0002): started next to the next version, written to and read back by its
     * id without touching the next version, renamed, and released on its own, after which its id
     * addresses the release and it is no longer a draft.
     */
    @Test
    fun `a draft is started, written to by its id, renamed, and released on its own`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "drafts-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator { ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug), version = "1.0.0").execute() }
        val versions = "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/versions"
        fun call(method: HttpMethod, path: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, baseHeaders(apiKey)), String::class.java)

        val created = call(HttpMethod.POST, versions, """{"name":"Redesign"}""")
        assertThat(created.statusCode).describedAs(created.body).isEqualTo(HttpStatus.CREATED)
        val work = JsonPath.read<String>(created.body!!, "$.versionId")
        assertThat(created.headers.location.toString()).endsWith("/versions/$work")
        assertThat(JsonPath.read<String>(created.body!!, "$.status")).isEqualTo("draft")
        assertThat(JsonPath.read<String>(created.body!!, "$.name")).isEqualTo("Redesign")
        assertThat(JsonPath.read<String>(created.body!!, "$.base")).isEqualTo("1.0.0")
        val taken = call(HttpMethod.POST, versions, """{"name":"redesign"}""")
        assertThat(taken.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(JsonPath.read<String>(taken.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-draft-name-in-use")

        val written = call(HttpMethod.PATCH, "$versions/$work/themes/tha", """{"name":"Th, redesigned"}""")
        assertThat(written.statusCode).describedAs(written.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/$work/themes/tha").body!!, "$.name")).isEqualTo("Th, redesigned")
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/next/themes/tha").body!!, "$.name")).isEqualTo("Th")
        val listed = call(HttpMethod.GET, versions).body!!
        assertThat(JsonPath.read<List<String?>>(listed, "$.items[*].name")).containsExactly("Next version", "Redesign", null)
        assertThat(JsonPath.read<List<String?>>(listed, "$.items[*].releaseVersion")).containsExactly(null, null, "1.0.0")

        val renamed = call(HttpMethod.PATCH, "$versions/$work", """{"name":"Redesign 2027"}""")
        assertThat(renamed.statusCode).describedAs(renamed.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(renamed.body!!, "$.name")).isEqualTo("Redesign 2027")

        assertThat(call(HttpMethod.POST, "$versions/$work/mark-ready").statusCode).isEqualTo(HttpStatus.OK)
        val release = call(HttpMethod.POST, "$versions/$work/release", "{}")
        assertThat(release.statusCode).describedAs(release.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(release.body!!, "$.catalogVersion.releaseVersion")).`as`("a fix against its base").isEqualTo("1.0.1")
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/$work").body!!, "$.status")).`as`("its id addresses the release").isEqualTo("released")
        assertThat(call(HttpMethod.PATCH, "$versions/$work/themes/tha", """{"name":"Again"}""").statusCode).`as`("a release does not change").isEqualTo(HttpStatus.CONFLICT)
        assertThat(JsonPath.read<String>(call(HttpMethod.GET, "$versions/next/themes/tha").body!!, "$.name")).`as`("the next version is untouched").isEqualTo("Th")
        assertThat(call(HttpMethod.DELETE, "$versions/1.0.0").statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a draft's default theme and presets are set and read over REST, and a release carries them`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "themes-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        val versions = "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/versions"
        fun call(method: HttpMethod, path: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, baseHeaders(apiKey)), String::class.java)

        val set = call(HttpMethod.PATCH, "$versions/next", """{"defaultTheme":{"key":"tha"},"presets":[{"key":"heading-accent","name":"Heading accent"}]}""")
        assertThat(set.statusCode).describedAs(set.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(set.body!!, "$.defaultTheme.key")).isEqualTo("tha")
        assertThat(JsonPath.read<List<String>>(set.body!!, "$.presets[*].key")).containsExactly("heading-accent")

        assertThat(call(HttpMethod.POST, "$versions/next/mark-ready").statusCode).isEqualTo(HttpStatus.OK)
        val release = call(HttpMethod.POST, "$versions/next/release", "{}")
        assertThat(release.statusCode).describedAs(release.body).isEqualTo(HttpStatus.OK)
        val released = call(HttpMethod.GET, "$versions/${JsonPath.read<String>(release.body!!, "$.catalogVersion.releaseVersion")}").body!!
        assertThat(JsonPath.read<String>(released, "$.defaultTheme.key")).`as`("the release froze it").isEqualTo("tha")
        assertThat(JsonPath.read<List<String>>(released, "$.presets[*].name")).containsExactly("Heading accent")
    }

    @Test
    fun `parallel work is behind a release of the next version, which names it`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "behind-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator { ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = CatalogKey.of(catalogSlug), version = "1.0.0").execute() }
        val versions = "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/versions"
        fun call(method: HttpMethod, path: String, body: String? = null) = restTemplate.exchange(path, method, HttpEntity(body, baseHeaders(apiKey)), String::class.java)
        val work = JsonPath.read<String>(call(HttpMethod.POST, versions, """{"name":"Redesign"}""").body!!, "$.versionId")

        assertThat(call(HttpMethod.PATCH, "$versions/next/themes/tha", """{"name":"Th, fixed"}""").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(call(HttpMethod.POST, "$versions/next/mark-ready").statusCode).isEqualTo(HttpStatus.OK)
        val released = call(HttpMethod.POST, "$versions/next/release", "{}")

        assertThat(released.statusCode).describedAs(released.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(released.body!!, "$.draftsBehind")).containsExactly("Redesign")
        assertThat(JsonPath.read<List<String>>(call(HttpMethod.GET, "$versions/$work").body!!, "$.behind[*].releaseVersion")).containsExactly("1.0.1")
        assertThat(JsonPath.read<List<String>>(call(HttpMethod.GET, "$versions/next").body!!, "$.behind")).isEmpty()
    }

    @Test
    fun `discarding the next version while it alone holds the tenant's default theme is a held problem`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "held-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)
        withMediator {
            val catalogKey = CatalogKey.of(catalogSlug)
            ReleaseCatalogVersion(tenantKey = tenantKey, catalogKey = catalogKey, version = "1.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), CatalogId(catalogKey, TenantId(tenantKey))), "Seasonal").execute()
            SetTenantDefaultTheme(tenantKey, ThemeKey.of("seasonal"), catalogKey).execute()
        }
        val versions = "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/versions"

        val refused = restTemplate.exchange("$versions/next", HttpMethod.DELETE, HttpEntity(null, baseHeaders(apiKey)), String::class.java)

        assertThat(refused.statusCode).describedAs(refused.body).isEqualTo(HttpStatus.CONFLICT)
        assertThat(JsonPath.read<String>(refused.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-held")
        assertThat(JsonPath.read<List<String>>(refused.body!!, "$.heldBy")).containsExactly("the tenant's default theme (theme $catalogSlug/seasonal)")
    }

    @Test
    fun `release rejects a subscribed catalog with read only problem details`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        withMediator {
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenantKey, catalogKey = CatalogKey.of("epistola-demo")).execute()
        }

        // A subscribed catalog has no draft: its versions are the releases it installed.
        val response = restTemplate.exchange(
            "${versionPath(tenantKey, "epistola-demo")}/release",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/catalog-version-released")
    }

    @Test
    fun `a subscribed catalog lists nothing available once it has its source's release, and installing that again changes nothing`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        withMediator {
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenantKey, catalogKey = CatalogKey.of("epistola-demo")).execute()
        }
        val catalog = withMediator { CatalogApiPaths.catalog(tenantKey, "epistola-demo") }
        val installed = withMediator { app.epistola.suite.catalog.queries.ListCatalogReleases(tenantKey, CatalogKey.of("epistola-demo")).query().single().version }

        val available = restTemplate.exchange("$catalog/available-releases", HttpMethod.GET, HttpEntity<Void>(baseHeaders(apiKey)), String::class.java)
        assertThat(available.statusCode).describedAs(available.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<Any>>(available.body!!, "$.available")).isEmpty()

        val response = restTemplate.exchange("$catalog/available-releases/$installed/install", HttpMethod.POST, HttpEntity<Void>(baseHeaders(apiKey)), String::class.java)

        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<String>(response.body!!, "$.releaseVersion")).isEqualTo(installed)
        assertThat(JsonPath.read<Boolean>(response.body!!, "$.newlyInstalled")).`as`("the release is already here").isFalse
    }

    @Test
    fun `an authored catalog has no source, so asking what is available is a conflict`() {
        val (tenantKey, apiKey) = seedTenantAndKey()
        val catalogSlug = "upg-${UUID.randomUUID().toString().take(8)}"
        seedAuthoredCatalog(tenantKey, catalogSlug)

        val response = restTemplate.exchange(
            "${withMediator { CatalogApiPaths.catalog(tenantKey, catalogSlug) }}/available-releases",
            HttpMethod.GET,
            HttpEntity<Void>(baseHeaders(apiKey)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_PROBLEM_JSON)).isTrue()
        assertThat(JsonPath.read<String>(response.body!!, "$.type")).isEqualTo("https://epistola.app/errors/conflict")
    }

    private fun versionPath(tenantKey: TenantKey, catalogKey: String, version: String? = null): String = withMediator { CatalogApiPaths.version(tenantKey, catalogKey, version) }

    private fun seedAuthoredCatalog(tenantKey: TenantKey, slug: String) = withMediator {
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of(slug)
        CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Rel $slug").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("tha"), CatalogId(catalogKey, tenantId)), name = "Th").execute()
        // A release takes what is marked ready, so the theme is marked ready to be releasable.
        MarkCatalogReady(tenantKey, catalogKey).execute()
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v2+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v2+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.14.0 catalog-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("ca-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Catalog API Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "ca-it").execute()
        tenantKey to created.plaintextKey
    }
}
