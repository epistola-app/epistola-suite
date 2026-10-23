// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.merge.GetDraftMergeSides
import app.epistola.suite.catalog.merge.MergeReleaseIntoDraft
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
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
import app.epistola.suite.testing.parallelReleaseLines
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

    @Autowired
    private lateinit var objectMapper: tools.jackson.databind.ObjectMapper

    private val letters = CatalogKey.of("letters")

    private fun catalogId(tenant: Tenant) = CatalogId(letters, TenantId(tenant.id))

    private fun theme(tenant: Tenant, key: String) = ThemeId(ThemeKey.of(key), catalogId(tenant))

    private fun work(tenant: Tenant, id: String) = CatalogReleaseRef(tenant.id, letters, DraftRef.Id(id))

    /** The scenario above; the work's draft id. */
    private fun diverged(tenant: Tenant, alsoDefaultTheme: Boolean = false): String = withMediator {
        CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
        CreateTheme(theme(tenant, "brand"), "Brand").execute()
        CreateTheme(theme(tenant, "letterhead"), "Letterhead").execute()
        CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId(tenant)), "Invoice").execute().withRequiredDataExample()
        MarkCatalogReady(tenant.id, letters).execute()
        ReleaseCatalogDraft(tenant.id, letters, "1.0.0").execute()

        val work = CreateCatalogDraft(tenant.id, letters, "Redesign").execute().versionId
        UpdateTheme(theme(tenant, "brand"), name = "Brand redesigned", catalogVersion = work(tenant, work)).execute()

        UpdateTheme(theme(tenant, "brand"), name = "Brand, fixed").execute()
        UpdateTheme(theme(tenant, "letterhead"), name = "Letterhead, fixed").execute()
        if (alsoDefaultTheme) SetCatalogDefaultTheme(tenant.id, letters, CatalogDefaultTheme(ThemeKey.of("letterhead"), letters)).execute()
        MarkCatalogReady(tenant.id, letters).execute()
        // Another default theme is a minor change, so that release is 1.1.0.
        ReleaseCatalogDraft(tenant.id, letters, if (alsoDefaultTheme) "1.1.0" else "1.0.1").execute()
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
    fun `the catalog's page offers merging a version the next release lacks, as the draft's page does`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Merge Offer Overview")
            withMediator {
                CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                CreateTheme(theme(tenant, "brand"), "Brand").execute()
                CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId(tenant)), "Invoice").execute().withRequiredDataExample()
                MarkCatalogReady(tenant.id, letters).execute()
                ReleaseCatalogDraft(tenant.id, letters, "1.0.0").execute()

                // A fix published from separate work: the next release does not have it.
                val fix = CreateCatalogDraft(tenant.id, letters, "Fix").execute().versionId
                UpdateTheme(theme(tenant, "brand"), name = "Brand, fixed", catalogVersion = work(tenant, fix)).execute()
                MarkCatalogReady(tenant.id, letters, work(tenant, fix)).execute()
                ReleaseCatalogDraft(tenant.id, letters, "1.0.1", catalogVersion = work(tenant, fix)).execute()
            }
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters/browse").body!! }
        then {
            val page = result<String>()
            assertThat(page).contains("data-testid=\"catalog-draft-behind-release\"")
            assertThat(page).contains("data-testid=\"catalog-merge-open\"")
                .contains("/tenants/${tenant.id}/catalogs/letters/merge?release=1.0.1")
                .contains("Merge it to bring its changes in")
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
    fun `after a merge the draft's page asks whether to remove a catalog it no longer uses, and Remove removes it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        val shared = CatalogKey.of("shared")
        given {
            tenant = tenant("Merge Unused")
            work = diverged(tenant)
            withMediator {
                CreateCatalog(tenantKey = tenant.id, id = shared, name = "Shared").execute()
                CreateTheme(ThemeId(ThemeKey.of("base"), CatalogId(shared, TenantId(tenant.id))), "Base").execute()
                MarkCatalogReady(tenant.id, shared).execute()
                ReleaseCatalogDraft(tenant.id, shared, "1.0.0").execute()
                val catalog = GetCatalog(tenant.id, shared).query()!!
                UpdateCatalogDependencies(
                    tenant.id,
                    letters,
                    listOf(DeclaredCatalogDependency(catalog.registry, catalog.namespace, shared.value, releaseVersion = "1.0.0")),
                    catalogVersion = work(tenant, work),
                ).execute()
            }
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters@$work/merge", mapOf("release" to "1.0.1", "resolution[theme/brand]" to "OURS")) }
        then {
            val location = result<ResponseEntity<String>>().headers.getFirst("HX-Location")!!
            assertThat(location).contains("/catalogs/letters@$work/draft?merged=1.0.1")

            val page = getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/draft?merged=1.0.1").body!!
            assertThat(page).contains("data-testid=\"merge-unused-dependencies\"").contains("After merging v<span>1.0.1</span>").contains("<strong>Shared</strong>")
                .contains("name=\"alias\" value=\"shared\"")
            assertThat(getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/draft").body!!).`as`("asked after a merge only").doesNotContain("merge-unused-dependencies")

            postForm("/tenants/${tenant.id}/catalogs/letters@$work/dependencies/remove-unused", mapOf("alias" to "shared"))
            assertThat(withMediator { GetCatalogBorrowing(tenant.id, letters, work(tenant, work)).query().catalogs.filter { it.declared } }).isEmpty()
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
            assertThat(response.headers["HX-Location"]).containsExactly("/tenants/${tenant.id}/catalogs/letters@$work/draft?merged=1.0.1")
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

    @Test
    fun `a catalog setting the release changed is listed and taken like a resource`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Setting")
            work = diverged(tenant, alsoDefaultTheme = true)
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge?release=1.1.0").body!! }
        then {
            assertThat(result<String>()).contains("data-testid=\"catalog-merge-taken-row\" data-resource=\"catalog/defaultTheme\"")
                .contains("Default theme").contains("(Catalog setting)")
            postForm("/tenants/${tenant.id}/catalogs/letters@$work/merge", mapOf("release" to "1.1.0", "resolution[theme/brand]" to "OURS"))
            assertThat(withMediator { GetCatalogThemeSettings(tenant.id, letters, work(tenant, work)).query() }.defaultTheme?.themeKey).isEqualTo(ThemeKey.of("letterhead"))
        }
    }

    @Test
    fun `an entry shows what it goes with, so both are chosen in view of each other`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Goes With")
            work = diverged(tenant, alsoDefaultTheme = true)
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge?release=1.1.0").body!! }
        then {
            // 1.1.0's default theme is its letterhead theme, which 1.1.0 changed too.
            val row = Regex("""data-resource="catalog/defaultTheme"[\s\S]*?</tr>""").find(result<String>())!!.value
            assertThat(row).contains("data-testid=\"catalog-merge-goes-with\"").contains("Goes with Letterhead (Theme)")
        }
    }

    @Test
    fun `a merge confirmed in a dialog that is out of date is refused, and the dialog shows the merge as it is now`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        lateinit var token: String
        given {
            tenant = tenant("Merge Outdated")
            work = diverged(tenant)
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge?release=1.0.1").body!!
            token = Regex("""name="token" value="([0-9a-f]+)"""").find(dialog)!!.groupValues[1]
            // Meanwhile the work changes the letterhead theme too.
            withMediator { UpdateTheme(theme(tenant, "letterhead"), name = "Letterhead redesigned", catalogVersion = work(tenant, work)).execute() }
        }
        whenever {
            postForm("/tenants/${tenant.id}/catalogs/letters@$work/merge", mapOf("release" to "1.0.1", "token" to token, "resolution[theme/brand]" to "THEIRS"))
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body!!).contains("data-testid=\"catalog-merge-refused\"").contains("since you opened this")
                .contains("name=\"resolution[theme/letterhead]\"")
                .doesNotContain("value=\"$token\"")
            assertThat(withMediator { PreviewDraftMerge(tenant.id, letters, "1.0.1", work(tenant, work)).query() }.alreadyMerged).isFalse()
        }
    }

    @Test
    fun `without the release both come from the dialog says which to get, and offers no merge`() = fixture {
        lateinit var tenant: Tenant
        given {
            val producer = tenant("Merge Lineage Producer")
            tenant = tenant("Merge Lineage Receiver")
            withMediator {
                val lines = mediator.parallelReleaseLines(producer.id, CatalogKey.of("lines-ui"))
                ImportCatalogZip(tenantKey = tenant.id, zipBytes = lines.v110).execute()
                ImportCatalogZip(tenantKey = tenant.id, zipBytes = lines.v101).execute()
            }
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/lines-ui/merge?release=1.0.1").body!! }
        then {
            assertThat(result<String>()).contains("data-testid=\"catalog-merge-missing-base\"")
                .contains("To merge v1.0.1, this installation needs v1.0.0, the release both come from. Get it first (from a registry, the Exchange or a file).")
                .doesNotContain("data-testid=\"catalog-merge-submit\"")
            val refused = postForm("/tenants/${tenant.id}/catalogs/lines-ui/merge", mapOf("release" to "1.0.1"))
            assertThat(refused.statusCode.value()).isEqualTo(422)
            assertThat(refused.body!!).contains("data-testid=\"catalog-merge-refused\"").contains("needs v1.0.0")
        }
    }

    @Test
    fun `when no release both come from is known the dialog says every difference is a choice`() = fixture {
        lateinit var tenant: Tenant
        given {
            val producer = tenant("Merge No Lineage Producer")
            tenant = tenant("Merge No Lineage Receiver")
            withMediator {
                val lines = mediator.parallelReleaseLines(producer.id, CatalogKey.of("no-lines-ui"))
                ImportCatalogZip(tenantKey = tenant.id, zipBytes = lines.v110).execute()
                ImportCatalogZip(tenantKey = tenant.id, zipBytes = withoutBase(lines.v101)).execute()
            }
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/no-lines-ui/merge?release=1.0.1").body!! }
        then {
            assertThat(result<String>()).contains("data-testid=\"catalog-merge-no-common-release\"")
                .contains("every difference between")
                .contains("name=\"resolution[theme/brand]\"")
                .contains("data-testid=\"catalog-merge-submit\"")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** [archive] as a release from before releases named their base. */
    private fun withoutBase(archive: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zip.readAllBytes()
                e = zip.nextEntry
            }
        }
        val manifest = objectMapper.readTree(entries["catalog.json"]!!) as tools.jackson.databind.node.ObjectNode
        (manifest.get("release") as tools.jackson.databind.node.ObjectNode).remove("base")
        entries["catalog.json"] = objectMapper.writeValueAsBytes(manifest)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a conflict can be compared on its three sides, read-only`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Compare")
            work = diverged(tenant)
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge/sides?release=1.0.1&entry=theme/brand") }
        then {
            val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge?release=1.0.1").body!!
            assertThat(dialog).contains("data-testid=\"catalog-merge-compare\"")
                .contains("/tenants/${tenant.id}/catalogs/letters@$work/merge/sides?release=1.0.1&amp;entry=theme/brand")
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(200)
            val sides = response.body!!
            assertThat(sides).contains("data-testid=\"catalog-merge-compare-sides\"").contains("When both started (v1.0.0)").contains("Yours").contains("v1.0.1’s")
            assertThat(sides.substringAfter("catalog-merge-compare-base").substringBefore("</pre>")).contains("&quot;name&quot; : &quot;Brand&quot;")
            assertThat(sides.substringAfter("catalog-merge-compare-draft").substringBefore("</pre>")).contains("&quot;name&quot; : &quot;Brand redesigned&quot;")
            assertThat(sides.substringAfter("catalog-merge-compare-release").substringBefore("</pre>")).contains("&quot;name&quot; : &quot;Brand, fixed&quot;")
            assertThat(getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/merge/sides?release=1.0.1&entry=theme/nothing").statusCode.value()).isEqualTo(404)
        }
    }

    /** [diverged], with the brand conflict merged as merged content (neither side's name), letterhead taken. */
    private fun mergedWithMergedContent(tenant: Tenant): String {
        val work = diverged(tenant)
        withMediator {
            val ref = work(tenant, work)
            MarkCatalogReady(tenant.id, letters, catalogVersion = ref).execute()
            val sides = GetDraftMergeSides(tenant.id, letters, "1.0.1", "theme/brand", ref).query()
            MergeReleaseIntoDraft(tenant.id, letters, "1.0.1", catalogVersion = ref, mergedContent = mapOf("theme/brand" to sides.draft!! + ("name" to "Brand, redesigned and fixed"))).execute()
        }
        return work
    }

    private fun row(page: String, resource: String) = page.substringAfter("data-resource=\"$resource\"").substringBefore("</tr>")

    @Test
    fun `a conflict merged with merged content is marked on the draft's page for review`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Merged Content")
            work = mergedWithMergedContent(tenant)
        }
        whenever { get("/tenants/${tenant.id}/catalogs/letters@$work/draft").body!! }
        then {
            val page = result<String>()
            assertThat(row(page, "theme/brand")).contains("Changed").contains("Brand, redesigned and fixed")
                .contains("data-testid=\"catalog-change-merged\"").contains("Merged, review before publishing")
            assertThat(row(page, "theme/letterhead")).`as`("taken from the release, not merged").contains("Letterhead, fixed").doesNotContain("catalog-change-merged")
            assertThat(page).contains("data-testid=\"catalog-not-ready-hint\"").contains("1 change is not marked ready yet")
        }
    }

    @Test
    fun `merged content is no longer marked once it is marked ready`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Merged Reviewed")
            work = mergedWithMergedContent(tenant)
        }
        whenever { postForm("/tenants/${tenant.id}/catalogs/letters@$work/ready", emptyMap()) }
        then {
            val marked = result<ResponseEntity<String>>()
            assertThat(marked.statusCode.value()).isEqualTo(200)
            assertThat(marked.body!!).doesNotContain("catalog-change-merged")
            val page = get("/tenants/${tenant.id}/catalogs/letters@$work/draft").body!!
            assertThat(row(page, "theme/brand")).contains("Brand, redesigned and fixed").doesNotContain("catalog-change-merged")
            assertThat(page).doesNotContain("catalog-not-ready-hint")
        }
    }

    @Test
    fun `the publish dialog names merged content to review before publishing it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var work: String
        given {
            tenant = tenant("Merge Merged Publish")
            work = mergedWithMergedContent(tenant)
        }
        whenever { getHtmx("/tenants/${tenant.id}/catalogs/letters@$work/release") }
        then {
            val dialog = result<ResponseEntity<String>>()
            assertThat(dialog.statusCode.value()).isEqualTo(200)
            val body = dialog.body!!
            assertThat(body).contains("data-testid=\"publish-merged\"")
            assertThat(body.substringAfter("data-testid=\"publish-merged-names\"").substringBefore("</p>"))
                .contains("These were merged, review them: Brand, redesigned and fixed (Theme).")
            val changes = body.split("data-testid=\"publish-change\"").drop(1).map { it.substringBefore("</li>") }
            assertThat(changes.single { "Brand, redesigned and fixed" in it }).contains("data-testid=\"publish-change-merged\"")
            assertThat(changes.single { "Letterhead, fixed" in it }).doesNotContain("publish-change-merged")
        }
    }

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
