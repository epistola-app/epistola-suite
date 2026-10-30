// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
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
import app.epistola.suite.testing.testCatalog
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

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/draft", String::class.java) }

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
    fun `a new catalog with nothing in it says so, rather than showing an empty card`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Next Release Empty")
            withMediator { CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("empty"), name = "Empty").execute() }
        }

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/empty/draft", String::class.java) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("data-testid=\"catalog-nothing-added\"")
            assertThat(body).doesNotContain("data-testid=\"catalog-no-changes\"")
        }
    }

    @Test
    fun `the publish dialog does not hold back for ready marks, which publishing sets itself`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Release Dialog Blocked")
            seedCatalogWithTemplate(tenant, "letters", "letter")
        }

        whenever { getHtmx("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/release") }

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

        whenever { postForm("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/release", mapOf("version" to "1.0.0")) }

        then {
            assertThat(withMediator { GetCatalog(testCatalog(tenant.id, CatalogKey.of("letters"))).query() }!!.latestReleaseVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `Mark all ready clears the block and offers Release`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Mark All Ready")
            seedCatalogWithTemplate(tenant, "letters", "letter")
        }

        whenever { postHtmx("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/ready") }

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

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/draft", String::class.java) }

        then {
            val page = result<ResponseEntity<String>>().body!!
            assertThat(page).contains("has no number until it is published").doesNotContain("/letters/renumber")
            assertThat(getHtmx("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/release").body!!).contains("It will be published as <strong>v2.0.0</strong>")
            assertThat(restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/renumber", String::class.java).statusCode)
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

        whenever { postForm("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/release", mapOf("version" to "1.0.1")) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("needs at least 2.0.0").contains("template &#39;letter&#39;: removed")
            assertThat(withMediator { ListCatalogReleaseSummaries(testCatalog(tenant.id, CatalogKey.of("letters"))).query() }.map { it.version }).containsExactly("1.0.0")
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
                "/tenants/${tenant.id}/catalogs/${tenant.id}/letters/dependencies",
                mapOf("catalog" to dependencyValue, "version" to ""),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Back on the Dependencies page, which lists it.
            assertThat(response.headers.getFirst("HX-Location").orEmpty()).contains("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/dependencies")
            val declared = withMediator { ListCatalogDependencies(testCatalog(tenant.id, CatalogKey.of("letters"))).query() }.single()
            assertThat(declared.catalogKey).isEqualTo("shared")
            assertThat(declared.releaseVersion).isEqualTo("1.0.0")
        }
    }

    /**
     * Each dependency with the name the catalog's content uses for it (ADR 0034), next to its
     * namespace and key: always the key here, and still what a reference into it says.
     */
    @Test
    fun `the draft's dependencies show the name the content uses for each`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Dependencies Alias")
            seedCatalogWithTemplate(tenant, "letters", "letter")
            val shared = seedCatalogWithTemplate(tenant, "shared", "header")
            withMediator {
                mediator.publishAndRelease(shared)
                val target = GetCatalog(testCatalog(tenant.id, CatalogKey.of("shared"))).query()!!
                UpdateCatalogDependencies(testCatalog(tenant.id, CatalogKey.of("letters")), listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "1.0.0"))).execute()
            }
        }

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/draft", String::class.java) }

        then {
            val table = result<ResponseEntity<String>>().body!!.substringAfter("data-testid=\"catalog-dependencies\"").substringBefore("</table>")
            assertThat(table).contains("<th title=\"What the templates, themes and stencils of this catalog call it\">Named in content as</th>")
            val row = table.substringAfter("data-testid=\"catalog-dependency-row\" data-catalog=\"shared\"").substringBefore("</tr>")
            assertThat(row.substringAfter("data-testid=\"catalog-dependency-alias\"").substringBefore("</td>")).isEqualTo("><code>shared</code>")
            assertThat(row).contains("v1.0.0")
        }
    }

    /**
     * A dependency is content of the release (ADR 0034): adding one to a draft whose resources are all
     * published is something to publish, listed in words on the draft's page and in the dialog.
     */
    @Test
    fun `a dependency added to a published draft is listed, and publishing it is offered`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Dependency Added")
            val letters = seedCatalogWithTemplate(tenant, "letters", "letter")
            val shared = seedCatalogWithTemplate(tenant, "shared", "header", name = "Shared")
            withMediator {
                mediator.publishAndRelease(letters)
                mediator.publishAndRelease(shared)
                val target = GetCatalog(testCatalog(tenant.id, CatalogKey.of("shared"))).query()!!
                UpdateCatalogDependencies(testCatalog(tenant.id, CatalogKey.of("letters")), listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "1.0.0"))).execute()
            }
        }

        whenever { restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/draft", String::class.java) }

        then {
            val page = result<ResponseEntity<String>>().body!!
            assertThat(page).doesNotContain("data-testid=\"catalog-no-changes\"")
            assertThat(page).contains("data-testid=\"catalog-release-open\"")
            val row = page.substringAfter("data-testid=\"catalog-change-row\" data-resource=\"catalogDependency/shared\"").substringBefore("</tr>")
            assertThat(row).contains(">Added<").contains(">Shared<").contains(">(Dependency)<").contains(">— locked to v1.0.0<")

            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/release").body!!
            assertThat(dialog).doesNotContain("data-testid=\"publish-nothing\"")
            val change = dialog.substringAfter("data-testid=\"publish-change\"").substringBefore("</li>")
            assertThat(change).contains(">Added<").contains("<strong>Shared</strong>").contains(">(Dependency)<").contains(">— locked to v1.0.0<")
            assertThat(dialog).`as`("a dependency added is an addition").contains("It will be published as <strong>v1.1.0</strong>")
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
                "/tenants/${tenant.id}/catalogs/${tenant.id}/letters/dependencies",
                mapOf("catalog" to dependencyValue, "version" to "2.0.0"),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body).contains("data-testid=\"catalog-dependency-error\"").contains("no release 2.0.0 of shared")
            assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant.id, CatalogKey.of("letters"))).query() }).`as`("nothing declared").isEmpty()
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
                "/tenants/${tenant.id}/catalogs/${tenant.id}/letters/dependencies",
                mapOf("catalog" to "exchange.epistola.app~nobody/missing", "version" to ""),
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body).contains("data-testid=\"catalog-dependency-error\"")
            assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant.id, CatalogKey.of("letters"))).query() }).isEmpty()
        }
    }

    /** An authored catalog [catalogKey] holding one template [templateKey] with a releasable contract. */
    private fun seedCatalogWithTemplate(tenant: Tenant, catalogKey: String, templateKey: String, name: String = catalogKey): VariantId = withMediator {
        val key = CatalogKey.of(catalogKey)
        CreateCatalog(tenantKey = tenant.id, id = key, name = name).execute()
        val templateId = TemplateId(TemplateKey.of(templateKey), testCatalog(TenantId(tenant.id), key))
        CreateDocumentTemplate(id = templateId, name = templateKey).execute().withRequiredDataExample()
        VariantId(VariantKey.INITIAL, templateId)
    }

    /** The dependency dialog's option value for [catalogKey]: `registry~namespace/key`. */
    private fun catalogValue(tenant: Tenant, catalogKey: String): String = withMediator {
        val catalog = GetCatalog(testCatalog(tenant.id, CatalogKey.of(catalogKey))).query()!!
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
