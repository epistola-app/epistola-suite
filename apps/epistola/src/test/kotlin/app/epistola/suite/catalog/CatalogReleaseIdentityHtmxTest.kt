// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.catalog.revisions.ReleaseContentHashBackfill
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.recordBeforeContentHashes
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * The release page shows what identifies a release (ADR 0034): its content fingerprint, the other
 * releases in the tenant with the same content, and, for a release recorded before content
 * fingerprints, the fingerprint it was recorded with and why there are two. Which releases share
 * content is `ListReleasesWithSameContent`'s, tested on its own; this covers the wiring.
 */
class CatalogReleaseIdentityHtmxTest : BaseIntegrationTest() {

    @Autowired private lateinit var restTemplate: TestRestTemplate

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var backfill: ReleaseContentHashBackfill

    private val letters = CatalogKey.of("letters")

    /** Catalog [key] named [name], with one theme `brand`, released as 1.0.0. */
    private fun releasedWithBrand(tenant: TenantKey, key: CatalogKey, name: String) = withMediator {
        CreateCatalog(tenantKey = tenant, id = key, name = name).execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(key, TenantId(tenant))), "Brand").execute()
        MarkCatalogReady(tenant, key).execute()
        ReleaseCatalogDraft(tenantKey = tenant, catalogKey = key, version = "1.0.0").execute()
    }

    private fun page(tenant: TenantKey, catalog: CatalogKey, version: String): String = restTemplate
        .getForEntity("/tenants/${tenant.value}/catalogs/${catalog.value}/releases/$version", String::class.java)
        .body!!

    /** The markup of the element tagged [testId], up to its first closing [tag]. */
    private fun String.part(testId: String, tag: String = "div"): String = substringAfter("data-testid=\"$testId\"", "").substringBefore("</$tag>")

    @Test
    fun `a release shows its content fingerprint, and links the releases with the same content`() {
        val tenant = createTenant("Release identity same").id
        releasedWithBrand(tenant, letters, "Letters")
        releasedWithBrand(tenant, CatalogKey.of("forms"), "Forms")
        withMediator { ReleaseCatalogDraft(tenantKey = tenant, catalogKey = letters, version = "1.0.1", notes = "Only the notes are new").execute() }
        val contentHash = withMediator { GetCatalogReleaseDetail(tenant, letters, "1.0.1").query()!!.contentHash!! }

        val body = page(tenant, letters, "1.0.1")

        val shown = body.part("release-content-hash")
        assertThat(shown).contains("Content fingerprint")
        assertThat(body.part("release-content-hash-value", "code"))
            .`as`("short, the whole of it in the title")
            .contains("title=\"$contentHash\"")
            .endsWith(">${contentHash.take(12)}")
        assertThat(body).contains("id=\"release-content-hash\" value=\"$contentHash\"")
            .contains("data-copy-source=\"release-content-hash\"")
        assertThat(body).`as`("cut with a content fingerprint: one fingerprint, nothing to explain")
            .doesNotContain("data-testid=\"release-original-fingerprint\"", "data-testid=\"release-content-hash-pending\"")

        val same = body.part("release-same-as")
        assertThat(same).contains("The same release as")
        assertThat(same).contains("href=\"/tenants/${tenant.value}/catalogs/letters/releases/1.0.0\"").contains(">v1.0.0</a>")
        assertThat(same).`as`("another catalog's, by its name").contains("href=\"/tenants/${tenant.value}/catalogs/forms/releases/1.0.0\"").contains(">Forms v1.0.0</a>")
        assertThat(same.indexOf(">v1.0.0</a>")).`as`("the catalog's own first").isLessThan(same.indexOf(">Forms v1.0.0</a>"))
    }

    /**
     * Recorded before content fingerprints: the content fingerprint is being computed until the
     * backfill gives it one, and the fingerprint it was recorded with is shown beside it, saying why.
     */
    @Test
    fun `a release recorded before content fingerprints says its content fingerprint is being computed, then shows both`() {
        val tenant = createTenant("Release identity before").id
        releasedWithBrand(tenant, letters, "Letters")
        val v5 = withMediator { mediator.recordBeforeContentHashes(jdbi, tenant, letters, "1.0.0") }

        val pending = page(tenant, letters, "1.0.0")

        assertThat(pending.part("release-content-hash")).contains("Being computed").doesNotContain("data-copy-source")
        assertThat(pending.part("release-original-fingerprint"))
            .contains("Original fingerprint", "title=\"$v5\"", ">${v5.take(12)}</code>")
            .contains("This release was recorded before content fingerprints; it is being given one.")
        assertThat(pending).doesNotContain("data-testid=\"release-same-as\"")

        backfill.run()
        val contentHash = withMediator { GetCatalogReleaseDetail(tenant, letters, "1.0.0").query()!!.contentHash!! }
        val given = page(tenant, letters, "1.0.0")

        assertThat(given.part("release-content-hash-value", "code")).endsWith(">${contentHash.take(12)}")
        assertThat(given).doesNotContain("data-testid=\"release-content-hash-pending\"")
        assertThat(given.part("release-original-fingerprint"))
            .contains("title=\"$v5\"")
            .contains("This release was recorded before content fingerprints and has been given one.")
    }
}
