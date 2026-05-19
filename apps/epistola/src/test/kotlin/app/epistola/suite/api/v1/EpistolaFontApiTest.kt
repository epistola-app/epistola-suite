package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * HTTP-level coverage of the read-only Fonts REST surface. Mirrors
 * `CodeListApiIT`: asserts shape + status codes + readOnly flagging, plus the
 * deliberate read-only scoping (no create/update/delete — the asset
 * precedent).
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
class EpistolaFontApiTest : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Test
    fun `list system fonts returns the eight bundled families with readOnly=true`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/fonts",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val slugs: List<String> = JsonPath.read(body, "$.items[*].slug")
        assertThat(slugs).hasSize(8)
        assertThat(slugs).contains("inter", "roboto", "jetbrains-mono")
        val readOnly: List<Boolean> = JsonPath.read(body, "$.items[*].readOnly")
        assertThat(readOnly).allMatch { it }
        val catalogTypes: List<String> = JsonPath.read(body, "$.items[*].catalogType")
        assertThat(catalogTypes).allMatch { it == "SUBSCRIBED" }
    }

    @Test
    fun `get single system font returns its variants`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/fonts/inter",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<String>(body, "$.slug")).isEqualTo("inter")
        assertThat(JsonPath.read<String>(body, "$.catalog")).isEqualTo("system")
        assertThat(JsonPath.read<Boolean>(body, "$.readOnly")).isTrue
        val weights: List<Int> = JsonPath.read(body, "$.variants[*].weight")
        assertThat(weights).containsExactlyInAnyOrder(400, 700, 400, 700)
        val italics: List<Boolean> = JsonPath.read(body, "$.variants[*].italic")
        assertThat(italics).containsExactlyInAnyOrder(false, false, true, true)
    }

    @Test
    fun `list AUTHORED catalog fonts after an upload`() {
        val (tenantKey, key) = seedTenantAndKey()
        withMediator {
            val asset = UploadAsset(
                tenantId = tenantKey,
                name = "acme-regular.ttf",
                mediaType = AssetMediaType.TTF,
                content = resourceLoader.getResource("classpath:epistola/fonts/inter/inter-Regular.ttf").contentAsByteArray,
                width = null,
                height = null,
                catalogKey = CatalogKey.DEFAULT,
            ).execute()
            ImportFont(
                tenantId = TenantId(tenantKey),
                catalogKey = CatalogKey.DEFAULT,
                slug = "acme-sans",
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = asset.id)),
            ).execute()
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/fonts",
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(JsonPath.read<List<String>>(body, "$.items[*].slug")).contains("acme-sans")
        val acmeReadOnly: List<Boolean> = JsonPath.read(body, "$.items[?(@.slug == 'acme-sans')].readOnly")
        assertThat(acmeReadOnly).allMatch { !it }
    }

    @Test
    fun `creating a font over REST is not supported`() {
        val (tenantKey, key) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/fonts",
            HttpMethod.POST,
            HttpEntity("{}", baseHeaders(key)),
            String::class.java,
        )
        // Deliberate read-only scoping — no POST mapping exists.
        assertThat(response.statusCode).isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN)
    }

    @Test
    fun `unauthenticated request is rejected`() {
        val (tenantKey, _) = seedTenantAndKey()
        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/fonts",
            HttpMethod.GET,
            HttpEntity<String>(null, HttpHeaders()),
            String::class.java,
        )
        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.4.0-SNAPSHOT font-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("ft-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Font Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "ft-it").execute()
        tenantKey to created.plaintextKey
    }
}
