package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Regression cover for the stencil detail HTMX fragment flow.
 *
 * The `versions` and `usage` fragments were rendered via [StencilHandler.versionListFragment]
 * and [StencilHandler.usageDetails] with a nullable `GetStencil(...).query()` value passed
 * straight into the model DSL. The DSL's `infix fun String.to(value: Any)` could not accept
 * nullable values, so Kotlin silently resolved to `kotlin.to` (the Pair extension), the
 * pair was discarded, and `stencil` never reached the model — yielding `EL1007E` on
 * `stencil.catalogType.name()` in `stencils/detail.html`. Each test below would have
 * returned HTTP 500 before the fix.
 */
@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class StencilHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX POST createVersion renders versions fragment with stencil model attribute`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedStencilWithoutDraft("Versions Fragment Create")
        }

        whenever {
            postHtmx("/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions")
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Versions table is the proof the fragment rendered to completion.
            // If `stencil` were null, Thymeleaf would have thrown EL1007E before reaching the table.
            assertThat(response.body).contains("ep-table")
            assertThat(response.body).contains("v1")
        }
    }

    @Test
    fun `HTMX POST publishVersion renders versions fragment with stencil model attribute`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedStencilWithDraft("Versions Fragment Publish")
        }

        whenever {
            postHtmx(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/versions/1/publish",
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // After publish, the row badge reads "published" — this only renders when
            // both `versions` and `stencil` are populated (the Publish/Archive button
            // visibility hinges on `stencil.catalogType.name()`).
            assertThat(response.body).contains("published")
        }
    }

    @Test
    fun `HTMX GET usage details renders usage fragment with stencil and catalogId`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedStencilWithPublished("Usage Fragment")
        }

        whenever {
            val headers = HttpHeaders().apply { set("HX-Request", "true") }
            restTemplate.exchange(
                "/tenants/${seeded.tenantId.key}/stencils/${seeded.catalogKey}/${seeded.stencilKey}/usage",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The upgrade picker is the visible proof; it requires `versions` AND the
            // surrounding markup uses `stencil` for the `AUTHORED` gate.
            assertThat(response.body).contains("upgrade-version-select")
        }
    }

    private data class Seeded(
        val tenantId: TenantId,
        val catalogKey: String,
        val stencilKey: String,
    )

    private fun seedStencilWithoutDraft(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        Seeded(tenantId, stencilId.catalogKey.value, stencilId.key.value)
    }

    private fun seedStencilWithDraft(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        CreateStencilVersion(stencilId = stencilId).execute()
        Seeded(tenantId, stencilId.catalogKey.value, stencilId.key.value)
    }

    private fun seedStencilWithPublished(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val stencilId = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
        CreateStencil(id = stencilId, name = name).execute()
        CreateStencilVersion(stencilId = stencilId).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
        Seeded(tenantId, stencilId.catalogKey.value, stencilId.key.value)
    }

    private fun postHtmx(url: String): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.postForEntity(url, HttpEntity<Void>(headers), String::class.java)
    }
}
