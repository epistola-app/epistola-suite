// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.revisions.ReleaseContentHashBackfill
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.recordBeforeContentHashes
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.testNamespace
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * "The same release as" (ADR 0034): the other releases in a tenant with the same content hash, in
 * any catalog and under any number, which the release page links.
 */
class ListReleasesWithSameContentTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var backfill: ReleaseContentHashBackfill

    private val letters = CatalogKey.of("letters")

    /** Catalog [key] named [name] in [tenant], with one theme `brand`, released as 1.0.0. */
    private fun releasedWithBrand(tenant: TenantKey, key: CatalogKey, name: String) = withMediator {
        CreateCatalog(tenantKey = tenant, id = key, name = name).execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), key)), "Brand").execute()
        MarkCatalogReady(testCatalog(tenant, key)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, key), version = "1.0.0").execute()
    }

    @Test
    fun `a release re-released with only new notes is the same release as the one before, and a changed one is not`() {
        val tenant = createTenant("Same content notes").id
        releasedWithBrand(tenant, letters, "Letters")
        withMediator {
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.1", notes = "Only the notes are new").execute()
            UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), letters)), name = "Brand, changed").execute()
            MarkCatalogReady(testCatalog(tenant, letters)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.2").execute()

            val first = ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.0").query()!!
            val reReleased = ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.1").query()!!

            assertThat(first.pending).isFalse()
            assertThat(first.contentHash).startsWith("v6:").isEqualTo(GetCatalogReleaseDetail(testCatalog(tenant, letters), "1.0.0").query()!!.contentHash)
            assertThat(first.releases).containsExactly(SameContentRelease("letters", "Letters", "1.0.1", sameCatalog = true, namespace = testNamespace(tenant, "letters")))
            assertThat(reReleased.contentHash).isEqualTo(first.contentHash)
            assertThat(reReleased.releases).containsExactly(SameContentRelease("letters", "Letters", "1.0.0", sameCatalog = true, namespace = testNamespace(tenant, "letters")))
            assertThat(ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.2").query()!!.releases).`as`("its content changed").isEmpty()
        }
    }

    /**
     * The content hash covers no catalog name: the same content released in another catalog of the
     * tenant is the same release, listed after the catalog's own. Another tenant's is not this
     * tenant's to show.
     */
    @Test
    fun `the same content in another catalog of the tenant is listed after the catalog's own, and another tenant's is not`() {
        val tenant = createTenant("Same content catalogs").id
        val elsewhere = createTenant("Same content elsewhere").id
        releasedWithBrand(tenant, letters, "Letters")
        releasedWithBrand(tenant, CatalogKey.of("forms"), "Forms")
        releasedWithBrand(elsewhere, letters, "Letters")
        withMediator { ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.1.0", notes = "The same again").execute() }

        val same = withMediator { ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.0").query()!! }

        assertThat(same.releases).containsExactly(
            SameContentRelease("letters", "Letters", "1.1.0", sameCatalog = true, namespace = testNamespace(tenant, "letters")),
            SameContentRelease("forms", "Forms", "1.0.0", sameCatalog = false, namespace = testNamespace(tenant, "forms")),
        )
        assertThat(withMediator { GetCatalogReleaseDetail(testCatalog(elsewhere, letters), "1.0.0").query()!!.contentHash })
            .`as`("the other tenant's release has the same content hash, and is not listed")
            .isEqualTo(same.contentHash)
    }

    /**
     * A release recorded before content hashes, which the backfill has not reached: nothing can be
     * said yet, and the page says it is being computed. Once computed, it is found again.
     */
    @Test
    fun `a release whose content hash is not computed yet is pending, and found once it is`() {
        val tenant = createTenant("Same content pending").id
        releasedWithBrand(tenant, letters, "Letters")
        withMediator {
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.1", notes = "Only the notes are new").execute()
            mediator.recordBeforeContentHashes(jdbi, testCatalog(tenant, letters), "1.0.0")
        }

        withMediator {
            val pending = ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.0").query()!!
            assertThat(pending.pending).isTrue()
            assertThat(pending.contentHash).isNull()
            assertThat(pending.releases).isEmpty()
            assertThat(ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.1").query()!!.releases)
                .`as`("a release without its content hash yet is not known to be the same")
                .isEmpty()
        }

        backfill.run()

        withMediator {
            assertThat(ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.0").query()!!.releases.map { it.version }).containsExactly("1.0.1")
            assertThat(ListReleasesWithSameContent(testCatalog(tenant, letters), "1.0.1").query()!!.releases.map { it.version }).containsExactly("1.0.0")
        }
    }

    @Test
    fun `a release the catalog does not have is nothing`() {
        val tenant = createTenant("Same content missing").id
        releasedWithBrand(tenant, letters, "Letters")

        assertThat(withMediator { ListReleasesWithSameContent(testCatalog(tenant, letters), "9.9.9").query() }).isNull()
    }
}
