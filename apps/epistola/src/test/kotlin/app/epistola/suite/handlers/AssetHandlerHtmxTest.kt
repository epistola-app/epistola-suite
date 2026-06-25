package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * Regression cover for the assets list HTMX render flow.
 *
 * `images/list.html` rendered the media-type chip with `${asset.mediaType.name()}`.
 * `AssetMediaType` was an enum until #434 (`feat(fonts)!`) turned it into an open
 * value class with no `name()` method, so every render of the list with at least one
 * asset threw `TemplateProcessingException` (`EL1004E`). The empty-state branch never
 * touched `mediaType`, so the page only broke once a tenant had uploaded an asset —
 * which is why no test caught it: nothing rendered this template with assets present.
 *
 * Both endpoints below render the same `mediaType` line. With the broken `.name()`
 * call they returned HTTP 500 before reaching any of these assertions.
 */
class AssetHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET assets list renders the page with the asset media type`() = fixture {
        var tenantKey = ""

        given {
            tenantKey = seedTenantWithPngAsset("Asset List Render", "logo.png")
        }

        whenever {
            restTemplate.getForEntity("/tenants/$tenantKey/images", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Reaching the grid + the rendered mime type proves the `mediaType` chip
            // rendered to completion. The old `.name()` call 500'd before this.
            assertThat(response.body).contains("asset-grid")
            assertThat(response.body).contains("logo.png")
            assertThat(response.body).contains("image/png")
        }
    }

    @Test
    fun `HTMX GET search renders the asset-grid-items fragment with the media type`() = fixture {
        var tenantKey = ""

        given {
            tenantKey = seedTenantWithPngAsset("Asset Search Render", "banner.png")
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/$tenantKey/images/search",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("banner.png")
            assertThat(response.body).contains("image/png")
        }
    }

    @Test
    fun `GET images list excludes font assets`() = fixture {
        var tenantKey = ""

        given {
            tenantKey = withMediator {
                val tenant: Tenant = createTenant("Images Excludes Fonts")
                UploadAsset(
                    tenantId = tenant.id,
                    name = "logo.png",
                    mediaType = AssetMediaType.PNG,
                    content = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                    width = 1,
                    height = 1,
                    catalogKey = CatalogKey.DEFAULT,
                ).execute()
                UploadAsset(
                    tenantId = tenant.id,
                    name = "brand-font.ttf",
                    mediaType = AssetMediaType.TTF,
                    content = byteArrayOf(0x05, 0x06, 0x07, 0x08),
                    width = null,
                    height = null,
                    catalogKey = CatalogKey.DEFAULT,
                ).execute()
                tenant.id.value
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/$tenantKey/images", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Images shows non-font assets; fonts are managed on their own page.
            assertThat(response.body).contains("logo.png")
            assertThat(response.body).doesNotContain("brand-font.ttf")
            assertThat(response.body).doesNotContain("font/ttf")
        }
    }

    @Test
    fun `GET images list keeps the catalog filter in the search and delete URLs`() = fixture {
        var tenantKey = ""

        given {
            tenantKey = seedTenantWithPngAsset("Images Catalog Filter", "logo.png")
        }

        whenever {
            restTemplate.getForEntity("/tenants/$tenantKey/images?catalog=default", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The active catalog filter rides along on both the search and the delete
            // request so an HTMX refresh stays consistent with the dropdown.
            assertThat(response.body).contains("/images/search?")
            assertThat(response.body).contains("catalog=default")
        }
    }

    @Test
    fun `JSON GET search includes each asset's catalog key`() = fixture {
        var tenantKey = ""

        given {
            tenantKey = seedTenantWithPngAsset("Asset Search Json", "logo.png")
        }

        whenever {
            val headers = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            restTemplate.exchange(
                "/tenants/$tenantKey/images/search",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The editor picker needs the catalog each image lives in to build a
            // cross-catalog reference, so the JSON list carries it.
            assertThat(response.body).contains("\"catalogKey\"")
            assertThat(response.body).contains("\"default\"")
        }
    }

    @Test
    fun `JSON GET catalogs returns the tenant catalogs for the picker chooser`() = fixture {
        var tenantKey = ""

        given {
            tenantKey = seedTenantWithPngAsset("Asset Catalogs Json", "logo.png")
        }

        whenever {
            val headers = HttpHeaders().apply { accept = listOf(MediaType.APPLICATION_JSON) }
            restTemplate.exchange(
                "/tenants/$tenantKey/images/catalogs",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("\"key\"")
            assertThat(response.body).contains("\"default\"")
        }
    }

    private fun seedTenantWithPngAsset(name: String, assetName: String): String = withMediator {
        val tenant: Tenant = createTenant(name)
        UploadAsset(
            tenantId = tenant.id,
            name = assetName,
            mediaType = AssetMediaType.PNG,
            content = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            width = 1,
            height = 1,
            catalogKey = CatalogKey.DEFAULT,
        ).execute()
        tenant.id.value
    }
}
