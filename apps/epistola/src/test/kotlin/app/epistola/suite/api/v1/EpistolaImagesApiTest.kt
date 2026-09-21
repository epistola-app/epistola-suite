// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

/**
 * HTTP-level coverage of the Images REST surface, which replaced the asset operations.
 *
 * The point of `/images` is two things the assets surface could not do: address an image by a
 * readable slug -- `AssetDto.id` was `format: uuid` -- and list images *only*, where the asset
 * operations mixed in the font-face binaries backing a font family. Both are asserted here, the
 * second by seeding a font alongside an image and checking it does not appear.
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
class EpistolaImagesApiTest : IntegrationTestBase() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Test
    fun `list reports a readable slug and leaves font faces out`() {
        val (tenantKey, key) = seedTenantAndKey()
        withMediator {
            UploadAsset(
                tenantId = tenantKey,
                name = "Municipality mark",
                mediaType = AssetMediaType.PNG,
                content = PNG,
                width = 1,
                height = 1,
                catalogKey = CatalogKey.DEFAULT,
                id = app.epistola.suite.common.ids.AssetKey.of("municipality-mark"),
            ).execute()
            // A font face is an assets row too. It must not surface here.
            val face = UploadAsset(
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
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = face.id)),
            ).execute()
        }

        val body = get("/api/tenants/${tenantKey.value}/catalogs/default/images", key)
        val slugs: List<String> = JsonPath.read(body, "$.items[*].slug")
        assertThat(slugs).containsExactly("municipality-mark")
        assertThat(JsonPath.read<Int>(body, "$.page.totalElements")).isEqualTo(1)
    }

    @Test
    fun `upload, download and delete round-trip by slug`() {
        val (tenantKey, key) = seedTenantAndKey()
        val base = "/api/tenants/${tenantKey.value}/catalogs/default/images"

        val parts = LinkedMultiValueMap<String, Any>().apply {
            add(
                "file",
                object : ByteArrayResource(PNG) {
                    override fun getFilename() = "mark.png"
                },
            )
            add("name", "Municipality mark")
        }
        val uploadHeaders = baseHeaders(key).apply { contentType = MediaType.MULTIPART_FORM_DATA }
        val uploaded = restTemplate.exchange(base, HttpMethod.POST, HttpEntity(parts, uploadHeaders), String::class.java)
        assertThat(uploaded.statusCode).isEqualTo(HttpStatus.CREATED)
        val slug = JsonPath.read<String>(uploaded.body!!, "$.slug")

        // The content endpoint answers with the image's own media type, so a JSON-only Accept
        // would be a 406 before the handler is ever reached.
        val binaryHeaders = baseHeaders(key).apply { accept = listOf(MediaType.ALL) }
        val content = restTemplate.exchange(
            "$base/$slug/content",
            HttpMethod.GET,
            HttpEntity<String>(null, binaryHeaders),
            ByteArray::class.java,
        )
        assertThat(content.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(content.body).isEqualTo(PNG)

        val deleted = restTemplate.exchange(
            "$base/$slug",
            HttpMethod.DELETE,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(deleted.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val afterDelete = restTemplate.exchange(
            "$base/$slug/content",
            HttpMethod.GET,
            HttpEntity<String>(null, binaryHeaders),
            String::class.java,
        )
        assertThat(afterDelete.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /** A font binary is not an image, and the upload says so rather than accepting it. */
    @Test
    fun `uploading a font binary is refused`() {
        val (tenantKey, key) = seedTenantAndKey()
        val parts = LinkedMultiValueMap<String, Any>().apply {
            add(
                "file",
                object : ByteArrayResource(
                    resourceLoader.getResource("classpath:epistola/fonts/inter/inter-Regular.ttf").contentAsByteArray,
                ) {
                    override fun getFilename() = "inter-Regular.ttf"
                },
            )
            add("mediaType", "font/ttf")
        }
        val headers = baseHeaders(key).apply { contentType = MediaType.MULTIPART_FORM_DATA }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/default/images",
            HttpMethod.POST,
            HttpEntity(parts, headers),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("is not an image")
    }

    /** Deleting an image that is not in the named catalog is a 404, not someone else's image. */
    @Test
    fun `an image in another catalog is not reachable`() {
        val (tenantKey, key) = seedTenantAndKey()
        withMediator {
            UploadAsset(
                tenantId = tenantKey,
                name = "Mark",
                mediaType = AssetMediaType.PNG,
                content = PNG,
                width = 1,
                height = 1,
                catalogKey = CatalogKey.DEFAULT,
                id = app.epistola.suite.common.ids.AssetKey.of("elsewhere-mark"),
            ).execute()
        }

        val response = restTemplate.exchange(
            "/api/tenants/${tenantKey.value}/catalogs/system/images/elsewhere-mark",
            HttpMethod.DELETE,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun get(path: String, key: String): String {
        val response = restTemplate.exchange(
            path,
            HttpMethod.GET,
            HttpEntity<String>(null, baseHeaders(key)),
            String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    private fun baseHeaders(apiKey: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set(HttpHeaders.USER_AGENT, "epistola-contract/1.3.0 images-it")
        set("X-EP-Node-Id", "test-node-${UUID.randomUUID()}")
        set("X-API-Key", apiKey)
    }

    private fun seedTenantAndKey(): Pair<TenantKey, String> = withMediator {
        val tenantKey = TenantKey.of("im-${UUID.randomUUID().toString().take(8)}")
        CreateTenant(id = tenantKey, name = "Image Tenant").execute()
        val created = CreateApiKey(tenantId = tenantKey, name = "im-it").execute()
        tenantKey to created.plaintextKey
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        )
    }
}
