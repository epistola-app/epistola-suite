// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * Every page that lists a tenant's catalogs, or what they hold, shows two catalogs called `letters`
 * (ADR 0034, phase 4) as two entries, each linked by its namespaced address.
 */
class TwoLettersPagesHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    private fun get(url: String): ResponseEntity<String> = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), String::class.java)

    private fun twoLetters(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            TestNamespaces.grant(tenant, "acme").execute()
            TestNamespaces.grant(tenant, "bureau").execute()
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
            CreateEnvironment(production, "Production").execute()
            for ((namespace, title) in listOf("acme" to "Acme", "bureau" to "Bureau")) {
                CreateCatalog(tenant, letters, "$title letters", namespace = namespace).execute()
                val catalog = CatalogId(namespace, letters, TenantId(tenant))
                val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog))
                CreateDocumentTemplate(variant.templateId, "$title invoice").execute()
                val version = mediator.publishAndRelease(variant)
                DeployCatalogRelease(production, catalog, version).execute()
            }
        }
        return tenant
    }

    /** The links of [body] to a catalog called `letters`, as the segment they name it by. */
    private fun lettersLinks(body: String): Set<String> = Regex("/catalogs/((?:[a-z0-9-]+/)?letters)/").findAll(body).map { it.groupValues[1] }.toSet()

    @Test
    fun `the tenant's pages show both letters, each linked by namespace and key`() {
        val tenant = twoLetters("Two letters pages").value
        for (page in listOf("", "/catalogs", "/catalogs/graph", "/environments/production", "/generation-history")) {
            val response = get("/tenants/$tenant$page")
            assertThat(response.statusCode.value()).`as`(page).isEqualTo(200)
            val body = response.body.orEmpty()
            assertThat(body).`as`(page).contains("Acme letters", "Bureau letters")
            // Never by key alone, which names both, nor with the namespace's slash escaped.
            assertThat(lettersLinks(body)).`as`(page).doesNotContain("letters")
            assertThat(body).`as`(page).doesNotContain("%2Fletters")
        }
        for (page in listOf("", "/catalogs", "/catalogs/graph", "/environments/production")) {
            assertThat(lettersLinks(get("/tenants/$tenant$page").body.orEmpty())).`as`(page).contains("acme/letters", "bureau/letters")
        }
    }

    @Test
    fun `a catalog's pages, reached by namespace and key, link that catalog the same way`() {
        val tenant = twoLetters("Two letters catalog pages").value
        val pages = listOf(
            "browse", "templates", "releases", "deployments", "dependencies", "settings", "stencils", "themes",
            "images", "fonts", "attributes", "code-lists", "templates/invoice",
        )
        for (page in pages) {
            val response = get("/tenants/$tenant/catalogs/bureau/letters/$page")
            assertThat(response.statusCode.value()).`as`(page).isEqualTo(200)
            val links = lettersLinks(response.body.orEmpty())
            assertThat(links).`as`(page).contains("bureau/letters").doesNotContain("letters")
            assertThat(response.body.orEmpty()).`as`(page).doesNotContain("%2Fletters")
        }
        // The catalog switcher links the other one by its own address.
        assertThat(lettersLinks(get("/tenants/$tenant/catalogs/bureau/letters/templates").body.orEmpty())).contains("acme/letters")
        // A namespace that does not hold the key names no catalog, as a key the tenant has none of does not.
        val elsewhere = get("/tenants/$tenant/catalogs/elsewhere/letters/browse")
        assertThat(elsewhere.statusCode.value()).isEqualTo(get("/tenants/$tenant/catalogs/nosuch/browse").statusCode.value())
        assertThat(elsewhere.body.orEmpty()).doesNotContain("Acme invoice", "Bureau invoice")
    }

    @Test
    fun `an environment takes either letters offline by namespace and key`() {
        val tenant = twoLetters("Two letters undeploy")
        val response = restTemplate.exchange(
            "/tenants/${tenant.value}/environments/production/deployments/bureau/letters/undeploy",
            HttpMethod.POST,
            HttpEntity(LinkedMultiValueMap<String, String>().apply { add("version", "1.0.0") }, HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }),
            String::class.java,
        )
        assertThat(response.statusCode.value()).`as`(response.body).isLessThan(400)
        val served = withMediator { ListDeployments(tenant, environmentKey = EnvironmentKey.of("production")).query() }
        assertThat(served.map { it.namespace }).containsExactly("acme")
    }

    @Test
    fun `a catalog whose key is also the name of a list is reached at its address`() {
        val tenant = createTenant("Key named templates").id
        withMediator {
            TestNamespaces.grant(tenant, "acme").execute()
            CreateCatalog(tenant, CatalogKey.of("templates"), "Templates catalog", namespace = "acme").execute()
        }

        val browse = get("/tenants/${tenant.value}/catalogs/acme/templates/browse")
        assertThat(browse.statusCode.value()).isEqualTo(200)
        assertThat(browse.body.orEmpty()).contains("Templates catalog").contains("/tenants/${tenant.value}/catalogs/acme/templates/settings")
    }

    @Test
    fun `an image is served from the catalog the template is in, by the alias it names it under`() {
        val tenant = twoLetters("Two letters images")
        withMediator {
            for (namespace in listOf("acme", "bureau")) {
                UploadAsset(
                    catalog = CatalogId(namespace, letters, TenantId(tenant)),
                    name = "Logo",
                    mediaType = AssetMediaType.SVG,
                    content = svg(namespace),
                    width = null,
                    height = null,
                    id = AssetKey.of("logo"),
                ).execute()
            }
        }

        for (namespace in listOf("acme", "bureau")) {
            // As the editor asks: from the template's catalog, by the alias the content names (ADR 0034, section 9).
            val image = restTemplate.getForEntity("/tenants/${tenant.value}/catalogs/$namespace/letters/images/logo/content?alias=letters", ByteArray::class.java)
            assertThat(image.statusCode.value()).`as`(namespace).isEqualTo(200)
            assertThat(String(image.body!!)).`as`(namespace).contains(namespace)
        }
        // The editor is told its template's catalog by address, which its image URLs are built from.
        val editor = get("/tenants/${tenant.value}/catalogs/bureau/letters/templates/invoice/variants/${VariantKey.INITIAL.value}/editor").body.orEmpty()
        assertThat(editor).contains("\"catalogSegment\": \"bureau/letters\"")
    }

    @Test
    fun `a deep link into organising names the resource by its catalog's address`() {
        val tenant = twoLetters("Two letters organise")
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
            CreateStencil(StencilId(StencilKey.of("header"), CatalogId("bureau", letters, TenantId(tenant))), "Bureau header").execute()
        }

        val page = get("/tenants/${tenant.value}/catalogs/organise?resource=stencil:bureau/letters/header")
        assertThat(page.body.orEmpty()).contains("data-preselected=\"stencil:bureau/letters/header\"")
        val offered = get("/tenants/${tenant.value}/catalogs/organise/resources?resource=stencil:bureau/letters/header").body.orEmpty()
        assertThat(offered).contains("stencil:bureau/letters/header").doesNotContain("stencil:acme/letters/header")
    }

    private fun svg(text: String) = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><title>$text</title></svg>""".toByteArray()
}
