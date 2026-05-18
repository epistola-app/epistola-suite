package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Handler-level coverage for the catalog release endpoint (preferred over a
 * browser test per the deterministic-only UI-test philosophy): an HTMX POST
 * cuts a release; a bad version re-renders the dialog with an error and does
 * not release.
 */
@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class CatalogReleaseHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    private fun seedAuthoredCatalog(tenant: Tenant, slug: String) {
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of(slug)
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Rel $slug").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("tha"), CatalogId(catalogKey, tenantId)), name = "Th").execute()
        }
    }

    @Test
    fun `htmx release cuts a version`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Release Handler Tenant")
            seedAuthoredCatalog(testTenant, "rel-cat")
        }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("version", "1.0.0")
            payload.add("notes", "first cut")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/catalogs/rel-cat/release",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val catalog = withMediator { GetCatalog(testTenant.id, CatalogKey.of("rel-cat")).query() }
            assertThat(catalog!!.releasedVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `bad version re-renders the dialog with an error and does not release`() = fixture {
        lateinit var testTenant: Tenant
        given {
            testTenant = tenant("Release Handler Bad Tenant")
            seedAuthoredCatalog(testTenant, "bad-cat")
        }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("version", "not-semver")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/catalogs/bad-cat/release",
                HttpEntity(payload, htmxForm()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("release-dialog")
            assertThat(response.body).containsIgnoringCase("SemVer")
            val catalog = withMediator { GetCatalog(testTenant.id, CatalogKey.of("bad-cat")).query() }
            assertThat(catalog!!.releasedVersion).isNull()
        }
    }
}
