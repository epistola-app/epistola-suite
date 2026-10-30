// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

class CatalogOrganiseRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun tenantWithStencil(name: String): String {
        val tenant = createTenant(name)
        val letters = CatalogKey.of("letters")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, CatalogKey.of("shared"), "Shared").execute()
            CreateStencil(StencilId(StencilKey.of("header"), testCatalog(TenantId(tenant.id), letters)), "Header").execute()
        }
        return tenant.id.value
    }

    @Test
    fun `the page is absent without the relocation toggle`() {
        val tenant = createTenant("Organise off")
        withMediator { SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = false).execute() }

        val response = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/organise", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the page renders the organise browser`() {
        val tenantId = tenantWithStencil("Organise standalone")

        val response = restTemplate.getForEntity("/tenants/$tenantId/catalogs/organise", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("<ep-catalog-organise")
    }

    @Test
    fun `a deep link preselects the resource it names`() {
        val tenantId = tenantWithStencil("Organise deep link")

        val response = restTemplate.getForEntity(
            "/tenants/$tenantId/catalogs/organise?resource=stencil:$tenantId/letters/header",
            String::class.java,
        )

        assertThat(response.body).contains("data-preselected=\"stencil:$tenantId/letters/header\"")
    }

    @Test
    fun `only relocatable types are offered`() {
        val tenantId = tenantWithStencil("Organise listing")

        val response = restTemplate.getForEntity("/tenants/$tenantId/catalogs/organise/resources", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // JsonNode has its own map() in Jackson 3, which shadows Kotlin's on Iterable.
        val types = buildList {
            objectMapper.readTree(response.body).path("resources").forEach { add(it.path("type").stringValue()) }
        }
        assertThat(types).isNotEmpty()
        // Every catalog resource type is movable; the listing offers nothing else.
        assertThat(types).allSatisfy { assertThat(it).isIn("template", "stencil", "theme", "font", "image", "codeList", "attribute") }
    }

    @Test
    fun `the browser offers only what the tenant can rearrange`() {
        val tenantId = tenantWithStencil("Organise authored only")
        // A system catalog is installed for every tenant and is not the tenant's to rearrange.
        val body = objectMapper.readTree(
            restTemplate.getForEntity("/tenants/$tenantId/catalogs/organise/resources", String::class.java).body,
        )

        val offeredCatalogs = buildList {
            body.path("resources").forEach { add(it.path("catalogKey").stringValue()) }
        }
        assertThat(offeredCatalogs).isNotEmpty()
        assertThat(offeredCatalogs).doesNotContain("system")

        // Nothing listed is read-only, so no resource carries a note saying so; the note is
        // reserved for consequences of moving, not reasons it cannot be moved.
        val notes = buildList {
            body.path("resources").forEach { node -> node.path("note").takeIf { it.isString }?.let { add(it.stringValue()) } }
        }
        assertThat(notes).allSatisfy { assertThat(it).doesNotContainIgnoringCase("read-only") }
    }

    @Test
    fun `the single-resource move is a dialog for HTMX and a page for a pasted link`() {
        val tenantId = tenantWithStencil("Organise single")
        val resource = "stencil:$tenantId/letters/header"
        val url = "/tenants/$tenantId/catalogs/organise/move?resource=$resource"

        val page = restTemplate.getForEntity(url, String::class.java)
        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(page.body).contains("data-single=\"$resource\"")
        // A pasted link has to stand on its own, so it carries the shell rather than a bare dialog.
        assertThat(page.body).doesNotContain("move-resource-dialog")

        val dialog = restTemplate.exchange(
            url,
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
            String::class.java,
        )
        assertThat(dialog.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(dialog.body).contains("move-resource-dialog")
        assertThat(dialog.body).contains("data-single=\"$resource\"")
    }

    @Test
    fun `the single-resource move is absent without the relocation toggle`() {
        val tenant = createTenant("Organise single off")
        withMediator { SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = false).execute() }

        val response = restTemplate.getForEntity(
            "/tenants/${tenant.id}/catalogs/organise/move?resource=stencil:${tenant.id}/letters/header",
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `preview then execute moves the selection`() {
        val tenantId = tenantWithStencil("Organise move")
        val body = """{"relocations":[{"type":"stencil","catalog":"$tenantId/letters","key":"header","targetCatalog":"$tenantId/shared"}]}"""

        val preview = post(tenantId, "preview", body)
        assertThat(preview.statusCode).isEqualTo(HttpStatus.OK)
        val plan = objectMapper.readTree(preview.body)
        assertThat(plan.path("executable").booleanValue()).isTrue()

        val fingerprint = plan.path("planFingerprint").stringValue()
        val executed = post(
            tenantId,
            "execute",
            """{"relocations":[{"type":"stencil","catalog":"$tenantId/letters","key":"header","targetCatalog":"$tenantId/shared"}],"planFingerprint":"$fingerprint"}""",
        )

        assertThat(executed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(
            objectMapper.readTree(executed.body).path("relocations").single().path("target").path("catalogKey").stringValue(),
        ).isEqualTo("shared")
    }

    @Test
    fun `the preview says what each next release becomes and in which order`() {
        val tenantId = tenantWithStencil("Organise releases")
        val body = """{"relocations":[{"type":"stencil","catalog":"$tenantId/letters","key":"header","targetCatalog":"$tenantId/shared"}]}"""

        val plan = objectMapper.readTree(post(tenantId, "preview", body).body)

        val releases = buildList { plan.path("releases").forEach { add(it.path("catalog").stringValue() to it.path("kind").stringValue()) } }
        assertThat(releases).contains("$tenantId/shared" to "ADDITIVE")
        assertThat(plan.path("releaseOrder").isArray).isTrue()
    }

    @Test
    fun `a move from a draft changes that draft, and the dialog carries the draft it was opened in`() {
        val tenantId = tenantWithStencil("Organise draft")
        val letters = testCatalog(TenantId(app.epistola.suite.common.ids.TenantKey.of(tenantId)), CatalogKey.of("letters"))
        val draft = withMediator { app.epistola.suite.catalog.releases.CreateCatalogDraft(letters, "Redesign", app.epistola.suite.catalog.releases.DraftRef.NEXT).execute().versionId }

        val dialog = restTemplate.exchange(
            "/tenants/$tenantId/catalogs/organise/move?resource=stencil:$tenantId/letters/header&draft=$draft",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
            String::class.java,
        )
        assertThat(dialog.body).contains("data-draft=\"$draft\"")

        val relocation = """{"type":"stencil","catalog":"$tenantId/letters","key":"header","targetCatalog":"$tenantId/shared"}"""
        val plan = objectMapper.readTree(post(tenantId, "preview", """{"relocations":[$relocation],"draft":"$draft"}""").body)
        // The draft worked in changes; the next version, which holds the stencil too, is left alone.
        val warnings = buildList { plan.path("warnings").forEach { add(it.path("message").stringValue()) } }
        assertThat(warnings).anySatisfy { assertThat(it).startsWith("The next release of ") }
        val executed = post(tenantId, "execute", """{"relocations":[$relocation],"draft":"$draft","planFingerprint":"${plan.path("planFingerprint").stringValue()}"}""")
        assertThat(executed.statusCode).`as`(executed.body).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `creating a shared catalog answers it as the listing names catalogs, and refuses a key in use`() {
        val tenantId = tenantWithStencil("Organise shared")

        val created = post(tenantId, "catalogs", """{"key":"brand","name":"Brand"}""")
        assertThat(created.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(created.body).path("key").stringValue()).isEqualTo("$tenantId/brand")

        val again = post(tenantId, "catalogs", """{"key":"brand","name":"Brand again"}""")
        assertThat(again.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(objectMapper.readTree(again.body).path("message").stringValue()).contains("brand")
    }

    /**
     * A tenant holding no namespace, as one upgraded from 1.x does, names its catalogs locally. The
     * shared catalog form has no namespace field, so it is named where those catalogs are, rather
     * than refused with advice to choose a namespace the form does not offer.
     */
    @Test
    fun `a tenant holding no namespace gets its shared catalog where its catalogs are`() {
        val tenant = TenantKey.of("org-local-${System.nanoTime() % 100000}")
        withMediator {
            CreateTenant(id = tenant, name = "Organise local").execute()
            SaveFeatureToggle(tenant, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
            // Named where 1.x left them: most of its catalogs under one local namespace.
            CreateCatalog(tenant, CatalogKey.of("letters"), "Letters", registry = LocalAuthority.NAME, namespace = "upgrade").execute()
            CreateCatalog(tenant, CatalogKey.of("forms"), "Forms", registry = LocalAuthority.NAME, namespace = "upgrade").execute()
        }

        val created = post(tenant.value, "catalogs", """{"key":"brand","name":"Brand"}""")

        assertThat(created.statusCode).`as`(created.body).isEqualTo(HttpStatus.OK)
        assertThat(objectMapper.readTree(created.body).path("key").stringValue()).isEqualTo("upgrade/brand")
    }

    private fun post(tenantId: String, path: String, body: String) = restTemplate.postForEntity(
        "/tenants/$tenantId/catalogs/organise/$path",
        HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
        String::class.java,
    )
}
