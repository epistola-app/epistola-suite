// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

class AssetRoutesTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `POST assets returns validation error for unsupported media type`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Asset Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.accept = listOf(MediaType.APPLICATION_JSON)

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource("not-an-image".toByteArray()) {
                        override fun getFilename(): String = "report.xlsx"
                    },
                    HttpHeaders().apply {
                        contentType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    },
                ),
            )
            payload.add("catalog", "default")

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
            assertThat(response.body).contains("Unsupported asset media type")
            assertThat(response.body).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        }
    }

    /**
     * An image named the way a person would is viewable, not a 500.
     *
     * This route parsed its `assetId` path variable with `UUID.fromString`, so the moment a catalog
     * named its images readably -- which the demo catalog now does -- every thumbnail on the images
     * page and every delete came back 500. The REST surface and the importer had the same
     * assumption removed; this one was missed because nothing exercised it with a text key.
     */
    @Test
    fun `GET image content serves an image whose key is a readable slug`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Readable Image Tenant")
            withMediator {
                UploadAsset(
                    tenantId = testTenant.id,
                    name = "Municipality mark",
                    mediaType = AssetMediaType.SVG,
                    content = SVG,
                    width = null,
                    height = null,
                    catalogKey = CatalogKey.DEFAULT,
                    id = AssetKey.of("municipality-mark"),
                ).execute()
            }
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/catalogs/default/images/municipality-mark/content",
                ByteArray::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.contentType).isEqualTo(MediaType.parseMediaType("image/svg+xml"))
            assertThat(response.body).isEqualTo(SVG)
            // Opened directly, the SVG is a document in our origin; it must stay a picture.
            assertThat(response.headers["X-Content-Type-Options"]).containsExactly("nosniff")
            assertThat(response.headers["Content-Security-Policy"]).containsExactly(SvgContentPolicy.SERVING_CONTENT_SECURITY_POLICY)
        }
    }

    @Test
    fun `POST assets refuses an SVG that references an external resource`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Unsafe SVG Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.accept = listOf(MediaType.APPLICATION_JSON)

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource(UNSAFE_SVG) {
                        override fun getFilename(): String = "logo.svg"
                    },
                    HttpHeaders().apply { contentType = MediaType.parseMediaType("image/svg+xml") },
                ),
            )
            payload.add("catalog", "default")

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body).contains("SVG not accepted")
            val stored = withMediator { ListAssets(tenantId = testTenant.id, catalogKey = CatalogKey.DEFAULT).query() }
            assertThat(stored).isEmpty()
        }
    }

    @Test
    fun `POST assets returns validation error for a filename containing markup`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Asset Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.MULTIPART_FORM_DATA
            headers.accept = listOf(MediaType.APPLICATION_JSON)

            val payload = LinkedMultiValueMap<String, Any>()
            payload.add(
                "file",
                HttpEntity(
                    object : ByteArrayResource("not-an-image".toByteArray()) {
                        override fun getFilename(): String = "evil<img src=x onerror=alert(1)>.png"
                    },
                    HttpHeaders().apply {
                        contentType = MediaType.IMAGE_PNG
                    },
                ),
            )
            payload.add("catalog", "default")

            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/images",
                HttpEntity(payload, headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_JSON)
            assertThat(response.body).contains("Name must not contain")
        }
    }

    private companion object {
        val SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"></svg>""".toByteArray()
        val UNSAFE_SVG = """<svg xmlns="http://www.w3.org/2000/svg"><image href="file:///etc/hosts.png"/></svg>""".toByteArray()
    }
}
