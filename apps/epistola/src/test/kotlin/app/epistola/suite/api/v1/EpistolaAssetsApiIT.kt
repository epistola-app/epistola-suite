package app.epistola.suite.api.v1

import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
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
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

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
class EpistolaAssetsApiIT : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `asset upload list download and delete round-trip`() {
        val (tenantKey, key) = seedTenantAndKey()
        val bytes = "asset-api-bytes".toByteArray()
        val payload = LinkedMultiValueMap<String, Any>().apply {
            add(
                "file",
                object : ByteArrayResource(bytes) {
                    override fun getFilename() = "api-asset.png"
                },
            )
            add("name", "API Asset")
            add("mediaType", "image/png")
        }

        val upload = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/assets",
            HttpMethod.POST,
            HttpEntity(payload, multipartHeaders(key)),
            String::class.java,
        )
        assertThat(upload.statusCode).isEqualTo(HttpStatus.CREATED)
        val assetId = JsonPath.read<String>(upload.body!!, "$.id")
        assertThat(JsonPath.read<String>(upload.body!!, "$.name")).isEqualTo("API Asset")
        assertThat(JsonPath.read<String>(upload.body!!, "$.mediaCategory")).isEqualTo("IMAGE")

        val list = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/assets?search=API",
            HttpMethod.GET,
            HttpEntity<String>(null, jsonHeaders(key)),
            String::class.java,
        )
        assertThat(list.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(JsonPath.read<List<String>>(list.body!!, "$.items[*].id")).contains(assetId)

        val download = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/assets/$assetId/content",
            HttpMethod.GET,
            HttpEntity<String>(null, binaryHeaders(key)),
            ByteArray::class.java,
        )
        assertThat(download.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(download.headers.contentType).isEqualTo(MediaType.IMAGE_PNG)
        assertThat(download.body).isEqualTo(bytes)

        val delete = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/assets/$assetId",
            HttpMethod.DELETE,
            HttpEntity<String>(null, jsonHeaders(key)),
            String::class.java,
        )
        assertThat(delete.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    private fun multipartHeaders(apiKey: String): HttpHeaders = baseHeaders(apiKey).apply {
        contentType = MediaType.MULTIPART_FORM_DATA
    }

    private fun jsonHeaders(apiKey: String): HttpHeaders = baseHeaders(apiKey).apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
    }

    private fun binaryHeaders(apiKey: String): HttpHeaders = baseHeaders(apiKey).apply {
        accept = listOf(MediaType.IMAGE_PNG, MediaType.ALL)
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        set(HttpHeaders.USER_AGENT, "epistola-contract/0.14.0 assets-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("asset-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Asset Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "asset-it").execute()
        tenantKey to created.plaintextKey
    }
}
