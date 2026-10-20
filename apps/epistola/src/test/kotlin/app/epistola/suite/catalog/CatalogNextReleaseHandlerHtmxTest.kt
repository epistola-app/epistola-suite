// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.withRequiredDataExample
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
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

/**
 * A draft's "Not published yet" section and the release dialog on the 2.0 model: the draft's
 * number with Renumber, the changes since the last release with Mark all ready, the declared
 * dependencies, and a Release that is withheld — with the reason — while anything blocks it.
 */
class CatalogNextReleaseHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the next version's page offers Publish, and says publishing marks the change that is not ready`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Next Release Blocked")
            seedCatalogWithTemplate(tenant, "letters", "letter")
        }

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/draft", String::class.java) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("data-testid=\"catalog-next-release\"")
            assertThat(body).`as`("numbered when published").contains("has no number until it is published")
            assertThat(body).doesNotContain("data-testid=\"catalog-release-blocked\"")
            assertThat(body).contains("1 change is not marked ready yet; publishing marks it.")
            assertThat(body).contains("data-testid=\"catalog-mark-ready\"")
            assertThat(body).contains("data-testid=\"catalog-release-open\"")
        }
    }

    @Test
    fun `the publish dialog does not hold back for ready marks, which publishing sets itself`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Release Dialog Blocked")
            seedCatalogWithTemplate(tenant, "letters", "letter")
        }

        whenever { getHtmx("/tenants/${tenant.id}/catalogs/letters/release") }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).doesNotContain("data-testid=\"release-blocked\"")
            assertThat(body).contains("data-testid=\"release-submit\"")
            // The version field is pre-filled with the draft's number.
            assertThat(body).contains("value=\"1.0.0\"")
        }
    }

    @Test
    fun `a publish posted while a resource is not ready marks it ready and publishes`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Release Refused")
            seedCatalogWithTemplate(tenant, "letters", "letter")
        }

        whenever { postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.0.0")) }

        then {
            assertThat(withMediator { GetCatalog(tenant.id, CatalogKey.of("letters")).query() }!!.releasedVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `Mark all ready clears the block and offers Release`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Mark All Ready")
            seedCatalogWithTemplate(tenant, "letters", "letter")
        }

        whenever { postHtmx("/tenants/${tenant.id}/catalogs/letters/ready") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).doesNotContain("data-testid=\"catalog-release-blocked\"")
            assertThat(body).doesNotContain("data-testid=\"catalog-mark-ready\"")
            assertThat(body).contains("data-testid=\"catalog-release-open\"")
        }
    }

    @Test
    fun `the next version has no number to change, and the publish dialog picks the number its changes need`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Number From Changes")
            val variant = seedCatalogWithTemplate(tenant, "letters", "letter")
            withMediator {
                mediator.publishAndRelease(variant)
                DeleteDocumentTemplate(variant.templateId).execute()
            }
        }

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/draft", String::class.java) }

        then {
            val page = result<ResponseEntity<String>>().body!!
            assertThat(page).contains("has no number until it is published").doesNotContain("/catalogs/letters/renumber")
            assertThat(getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!).contains("It will be published as <strong>v2.0.0</strong>")
            assertThat(restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/renumber", String::class.java).statusCode)
                .`as`("renumbering is gone").isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `publishing under a number lower than the changes need is refused in the dialog`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Number Too Low")
            val variant = seedCatalogWithTemplate(tenant, "letters", "letter")
            withMediator {
                mediator.publishAndRelease(variant)
                DeleteDocumentTemplate(variant.templateId).execute()
            }
        }

        whenever { postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.0.1")) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("needs at least 2.0.0").contains("template &#39;letter&#39;: removed")
            assertThat(withMediator { ListCatalogReleases(tenant.id, CatalogKey.of("letters")).query() }.map { it.version }).containsExactly("1.0.0")
        }
    }

    @Test
    fun `the dependency dialog declares a catalog, pinned to its highest release`() = fixture {
        lateinit var tenant: Tenant
        lateinit var dependencyValue: String

        given {
            tenant = tenant("Dependencies Declare")
            seedCatalogWithTemplate(tenant, "letters", "letter")
            val shared = seedCatalogWithTemplate(tenant, "shared", "header")
            withMediator { mediator.publishAndRelease(shared) }
            dependencyValue = catalogValue(tenant, "shared")
        }

        whenever {
            postForm(
                "/tenants/${tenant.id}/catalogs/letters/dependencies",
                mapOf("catalog" to dependencyValue, "version" to ""),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Back on the Dependencies page, which lists it.
            assertThat(response.headers.getFirst("HX-Location").orEmpty()).contains("/tenants/${tenant.id}/catalogs/letters/dependencies")
            val declared = withMediator { ListCatalogDependencies(tenant.id, CatalogKey.of("letters")).query() }.single()
            assertThat(declared.catalogKey).isEqualTo("shared")
            assertThat(declared.releaseVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `a release the catalog depended on does not have is refused on its row`() = fixture {
        lateinit var tenant: Tenant
        lateinit var dependencyValue: String

        given {
            tenant = tenant("Dependencies Unresolved")
            seedCatalogWithTemplate(tenant, "letters", "letter")
            val shared = seedCatalogWithTemplate(tenant, "shared", "header")
            withMediator { mediator.publishAndRelease(shared) }
            dependencyValue = catalogValue(tenant, "shared")
        }

        whenever {
            postForm(
                "/tenants/${tenant.id}/catalogs/letters/dependencies",
                mapOf("catalog" to dependencyValue, "version" to "2.0.0"),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body).contains("data-testid=\"catalog-dependency-error\"").contains("no release 2.0.0 of shared")
            assertThat(withMediator { ListCatalogDependencies(tenant.id, CatalogKey.of("letters")).query() }).`as`("nothing declared").isEmpty()
        }
    }

    @Test
    fun `the dependency dialog refuses a catalog the tenant does not have, on its row`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Dependencies Refused")
            seedCatalogWithTemplate(tenant, "letters", "letter")
            seedCatalogWithTemplate(tenant, "shared", "header")
        }

        whenever {
            postForm(
                "/tenants/${tenant.id}/catalogs/letters/dependencies",
                mapOf("catalog" to "exchange.epistola.app~nobody/missing", "version" to ""),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body).contains("data-testid=\"catalog-dependency-error\"")
            assertThat(withMediator { ListCatalogDependencies(tenant.id, CatalogKey.of("letters")).query() }).isEmpty()
        }
    }

    /** An authored catalog [catalogKey] holding one template [templateKey] with a releasable contract. */
    private fun seedCatalogWithTemplate(tenant: Tenant, catalogKey: String, templateKey: String): VariantId = withMediator {
        val key = CatalogKey.of(catalogKey)
        CreateCatalog(tenantKey = tenant.id, id = key, name = catalogKey).execute()
        val templateId = TemplateId(TemplateKey.of(templateKey), CatalogId(key, TenantId(tenant.id)))
        CreateDocumentTemplate(id = templateId, name = templateKey).execute().withRequiredDataExample()
        VariantId(VariantKey.INITIAL, templateId)
    }

    /** The dependency dialog's option value for [catalogKey]: `registry~namespace/key`. */
    private fun catalogValue(tenant: Tenant, catalogKey: String): String = withMediator {
        val catalog = GetCatalog(tenant.id, CatalogKey.of(catalogKey)).query()!!
        "${catalog.registry}~${catalog.namespace}/${catalog.id.value}"
    }

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun postHtmx(url: String): ResponseEntity<String> = restTemplate.postForEntity(
        url,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun postForm(url: String, fields: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val form: MultiValueMap<String, String> = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }
}
