// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.ObjectMapper

/**
 * End-to-end coverage for the customer font upload UI (mirrors
 * `app.epistola.suite.assets.AssetRoutesTest`): a multipart POST creates the
 * asset binaries + the font family from repeating (file, weight, italic) face
 * rows, SUBSCRIBED catalogs and a face-less submission are rejected, and the
 * family then appears in `ListFonts` + the JSON `/fonts/search` editor
 * surface.
 */
class FontUploadHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun ttf(): ByteArray = resourceLoader
        .getResource("classpath:epistola/fonts/inter/inter-Regular.ttf")
        .contentAsByteArray

    private fun facePart(name: String) = HttpEntity(
        object : ByteArrayResource(ttf()) {
            override fun getFilename(): String = name
        },
        HttpHeaders().apply { contentType = MediaType.parseMediaType("font/ttf") },
    )

    /** A face whose content type is font/ttf but whose bytes are not a valid font. */
    private fun badFacePart(name: String) = HttpEntity(
        object : ByteArrayResource("not-a-real-font".toByteArray()) {
            override fun getFilename(): String = name
        },
        HttpHeaders().apply { contentType = MediaType.parseMediaType("font/ttf") },
    )

    private fun multipartHeaders() = HttpHeaders().apply {
        contentType = MediaType.MULTIPART_FORM_DATA
        accept = listOf(MediaType.APPLICATION_JSON)
    }

    @Test
    fun `upload registers a font family with regular and bold faces`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Upload Tenant") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "acme-sans")
            payload.add("name", "Acme Sans")
            payload.add("kind", "sans")
            payload.add("catalog", "default")
            payload.add("file", facePart("acme-sans-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            payload.add("file", facePart("acme-sans-bold.ttf"))
            payload.add("weight", "700")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, multipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

            val tenantId = TenantId(testTenant.id)
            val fonts = withMediator { ListFonts(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query() }
            val acme = fonts.single { it.slug.value == "acme-sans" }
            assertThat(acme.name).isEqualTo("Acme Sans")
            assertThat(acme.kind.wire).isEqualTo("sans")

            // Visible in the editor-facing JSON search surface too.
            val search = restTemplate.getForEntity(
                "/tenants/${testTenant.id}/fonts/search?catalog=default",
                String::class.java,
            )
            val slugs = objectMapper.readTree(search.body).values().map { it["slug"].asString() }
            assertThat(slugs).contains("acme-sans")
        }
    }

    @Test
    fun `upload to the system SUBSCRIBED catalog is rejected`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font System Reject Tenant") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "rogue-sans")
            payload.add("name", "Rogue Sans")
            payload.add("kind", "sans")
            payload.add("catalog", "system")
            payload.add("file", facePart("rogue-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, multipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            val tenantId = TenantId(testTenant.id)
            val fonts = withMediator {
                ListFonts(tenantId = tenantId, catalogKey = CatalogKey.of("system")).query()
            }
            assertThat(fonts.map { it.slug.value }).doesNotContain("rogue-sans")
        }
    }

    @Test
    fun `upload without any face file is rejected`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font No Face Tenant") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "no-face")
            payload.add("name", "No Face")
            payload.add("kind", "display")
            payload.add("catalog", "default")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, multipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body).contains("At least one face file is required")
        }
    }

    @Test
    fun `upload rejects an invalid slug`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Bad Slug Tenant") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "Bad Slug!")
            payload.add("name", "Bad")
            payload.add("kind", "sans")
            payload.add("catalog", "default")
            payload.add("file", facePart("bad-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, multipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `a face that fails validation persists nothing - no partial write`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Partial Write") }

        whenever {
            // First face is a valid TTF, second is garbage with a font content
            // type. Every face is validated BEFORE any asset is persisted, so a
            // failure on the second must not leave the first one's asset behind.
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "acme-sans")
            payload.add("name", "Acme Sans")
            payload.add("kind", "sans")
            payload.add("catalog", "default")
            payload.add("file", facePart("acme-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            payload.add("file", badFacePart("acme-bold.ttf"))
            payload.add("weight", "700")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, multipartHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

            val tenantId = TenantId(testTenant.id)
            // No font family created …
            val fonts = withMediator { ListFonts(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query() }
            assertThat(fonts).isEmpty()
            // … and — the fix — the first (valid) face's asset was NOT persisted.
            val assetNames = withMediator { ListAssets(tenantId = testTenant.id).query() }.map { it.name }
            assertThat(assetNames).doesNotContain("acme-regular.ttf")
        }
    }

    @Test
    fun `delete removes an uploaded font family`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Font Delete UI Tenant") }

        whenever {
            val payload = LinkedMultiValueMap<String, Any>()
            payload.add("slug", "temp-sans")
            payload.add("name", "Temp Sans")
            payload.add("kind", "sans")
            payload.add("catalog", "default")
            payload.add("file", facePart("temp-regular.ttf"))
            payload.add("weight", "400")
            payload.add("italic", "false")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts",
                HttpEntity(payload, multipartHeaders()),
                String::class.java,
            )
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/fonts/default/temp-sans/delete",
                null,
                String::class.java,
            )
        }

        then {
            val tenantId = TenantId(testTenant.id)
            val fonts = withMediator { ListFonts(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query() }
            assertThat(fonts.map { it.slug.value }).doesNotContain("temp-sans")
            // FontId is unused-import-safe: assert it stays gone via query.
            val gone = withMediator {
                ListFonts(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query()
                    .none { it.slug == FontKey.of("temp-sans") }
            }
            assertThat(gone).isTrue()
        }
    }
}
