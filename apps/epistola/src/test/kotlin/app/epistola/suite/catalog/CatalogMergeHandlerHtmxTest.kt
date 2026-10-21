// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
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
 * Merging a published version into a draft from the draft's page (ADR 0032, alpha): parallel work
 * "Redesign" started from 1.0.0, then the next version was published as 1.0.1, so the work lacks
 * it. Both renamed the brand theme, which is a conflict; 1.0.1 also renamed the letterhead theme.
 */
class CatalogMergeHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: Tenant) = CatalogId(letters, TenantId(tenant.id))

    private fun theme(tenant: Tenant, key: String) = ThemeId(ThemeKey.of(key), catalogId(tenant))

    private fun work(tenant: Tenant, id: String) = CatalogVersionRef(tenant.id, letters, DraftRef.Id(id))

    /** The scenario above; the work's draft id. */
    private fun diverged(tenant: Tenant): String = withMediator {
        CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
        CreateTheme(theme(tenant, "brand"), "Brand").execute()
        CreateTheme(theme(tenant, "letterhead"), "Letterhead").execute()
        CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId(tenant)), "Invoice").execute().withRequiredDataExample()
        MarkCatalogReady(tenant.id, letters).execute()
        ReleaseCatalogVersion(tenant.id, letters, "1.0.0").execute()

        val work = CreateCatalogDraft(tenant.id, letters, "Redesign").execute().versionId
        UpdateTheme(theme(tenant, "brand"), name = "Brand redesigned", catalogVersion = work(tenant, work)).execute()

        UpdateTheme(theme(tenant, "brand"), name = "Brand, fixed").execute()
        UpdateTheme(theme(tenant, "letterhead"), name = "Letterhead, fixed").execute()
        MarkCatalogReady(tenant.id, letters).execute()
        ReleaseCatalogVersion(tenant.id, letters, "1.0.1").execute()
        work
    }

    @Test
    fun `the draft's page offers merging the version it lacks`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Offer")
            work = diverged(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters@$work/draft").body!! }
        then {
            val page = result<String>()
            assertThat(page).contains("data-testid=\"catalog-draft-behind-release\"")
            assertThat(page).contains("data-testid=\"catalog-merge-open\"")
                .contains("/tenants/${tenant.id}/catalogs/letters@$work/merge?release=1.0.1")
                .doesNotContain("data-testid=\"catalog-merge-withheld\"")
        }
    }

    @Test
    fun `the merge dialog shows the conflict with both choices, what is taken, and that it is alpha`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Preview")
            work = diverged(tenant)
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge?release=1.0.1") }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(200)
            val dialog = response.body!!
            assertThat(dialog).contains("id=\"catalog-merge-dialog\"").contains("Merge v1.0.1")
            assertThat(dialog).contains("data-testid=\"catalog-merge-alpha\"")
            assertThat(dialog).contains("Brings the changes of <strong>v1.0.1</strong> into").contains("<strong>Redesign</strong>")
            assertThat(dialog).contains("data-resource=\"theme/brand\"")
                .contains("name=\"resolution[theme/brand]\" value=\"OURS\"")
                .contains("name=\"resolution[theme/brand]\" value=\"THEIRS\"")
                .contains("Keep yours").contains("Take v1.0.1’s")
            assertThat(dialog).contains("data-testid=\"catalog-merge-taken-row\" data-resource=\"theme/letterhead\"").contains("Changed in v1.0.1")
            assertThat(dialog).contains(">Merge</button>")
        }
    }

    @Test
    fun `merging without a choice for the conflict shows the dialog again with why`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Unresolved")
            work = diverged(tenant)
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters@$work/merge", mapOf("release" to "1.0.1")) }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).`as`("a refusal is not a success").isEqualTo(422)
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#catalog-merge-form")
            assertThat(response.body!!).contains("data-testid=\"catalog-merge-refused\"").contains("Choose which one to keep")
                .contains("name=\"resolution[theme/brand]\"")
            assertThat(withMediator { PreviewDraftMerge(tenant.id, letters, "1.0.1", work(tenant, work)).query() }.alreadyMerged).isFalse()
        }
    }

    @Test
    fun `a stray choice is refused and the choices made are kept`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Stray")
            work = diverged(tenant)
        }
        whenever {
            postForm(
                "/tenants/${tenant.id}/catalogs/letters@$work/merge",
                mapOf("release" to "1.0.1", "resolution[theme/brand]" to "THEIRS", "resolution[theme/letterhead]" to "OURS"),
            )
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body!!).contains("Not a conflict of this merge: theme/letterhead")
                .containsPattern("""name="resolution\[theme/brand]" value="THEIRS"[^>]*checked""")
        }
    }

    @Test
    fun `merging with a choice takes the release's side, and the draft no longer lacks it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Apply")
            work = diverged(tenant)
        }
        whenever {
            postForm("/tenants/${tenant.id}/catalogs/letters@$work/merge", mapOf("release" to "1.0.1", "resolution[theme/brand]" to "THEIRS"))
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(200)
            assertThat(response.headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters@$work/draft")
            val ref = work(tenant, work)
            assertThat(withMediator { PreviewDraftMerge(tenant.id, letters, "1.0.1", ref).query() }.alreadyMerged).isTrue()
            assertThat(withMediator { GetTheme(theme(tenant, "brand"), catalogVersion = ref).query() }!!.name).isEqualTo("Brand, fixed")
            assertThat(withMediator { GetTheme(theme(tenant, "letterhead"), catalogVersion = ref).query() }!!.name).isEqualTo("Letterhead, fixed")
            assertThat(get("/tenants/${tenant.id}/catalogs/letters@$work/draft").body!!).doesNotContain("data-testid=\"catalog-merge-open\"")
        }
    }

    @Test
    fun `keeping yours leaves the conflict as the draft has it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Keep")
            work = diverged(tenant)
        }
        whenever {
            postForm("/tenants/${tenant.id}/catalogs/letters@$work/merge", mapOf("release" to "1.0.1", "resolution[theme/brand]" to "OURS"))
        }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode.value()).isEqualTo(200)
            val ref = work(tenant, work)
            assertThat(withMediator { GetTheme(theme(tenant, "brand"), catalogVersion = ref).query() }!!.name).isEqualTo("Brand redesigned")
            assertThat(withMediator { GetTheme(theme(tenant, "letterhead"), catalogVersion = ref).query() }!!.name).isEqualTo("Letterhead, fixed")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun postForm(url: String, fields: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }
}
