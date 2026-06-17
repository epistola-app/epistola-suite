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

/**
 * Regression cover for the assets list HTMX render flow.
 *
 * `assets/list.html` rendered the media-type chip with `${asset.mediaType.name()}`.
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
            restTemplate.getForEntity("/tenants/$tenantKey/assets", String::class.java)
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
                "/tenants/$tenantKey/assets/search",
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
