// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.assets.AssetIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.ListVisibleImages
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.ListVisibleCodeLists
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclareCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.ListVisibleFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.queries.ListVisibleStencils
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withoutSystemCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A picker offers what a catalog version can use, and apart from it what it could use once it
 * declares the catalog ([Reach.UNDECLARED]); declaring one ([DeclareCatalogDependency]) moves its
 * resources from the one to the other, at the version the declaration names.
 */
class DeclareBeforeUseTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun catalogs(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator { listOf(letters, shared).forEach { CreateCatalog(tenant, it, it.value).execute() } }
        return tenant
    }

    private fun stencil(tenant: TenantKey, catalog: CatalogKey, key: String, name: String) = withMediator {
        CreateStencil(StencilId(StencilKey.of(key), testCatalog(tenant, catalog)), name, catalogVersion = null).execute()
    }

    private fun stencils(tenant: TenantKey, reach: Reach, search: String? = null) = withMediator {
        ListVisibleStencils(testCatalog(tenant, letters), catalogVersion = null, searchTerm = search, reach = reach).query().map { it.name }
    }

    private fun declare(tenant: TenantKey, dependency: app.epistola.suite.common.ids.CatalogId) = withMediator {
        DeclareCatalogDependency(testCatalog(tenant, letters), dependency, catalogVersion = null).execute()
    }

    @Test
    fun `an undeclared catalog's stencils are offered apart, and declaring it makes them usable`() {
        val tenant = catalogs("declare-stencils")
        stencil(tenant, letters, "letterhead", "Letterhead")
        stencil(tenant, shared, "header", "Shared header")

        assertThat(stencils(tenant, Reach.DECLARED)).containsExactly("Letterhead")
        assertThat(stencils(tenant, Reach.UNDECLARED)).containsExactly("Shared header")

        declare(tenant, testCatalog(tenant, shared))

        assertThat(stencils(tenant, Reach.DECLARED)).containsExactly("Letterhead", "Shared header")
        assertThat(stencils(tenant, Reach.UNDECLARED)).isEmpty()
    }

    @Test
    fun `a search narrows both what can be used and what could be`() {
        val tenant = catalogs("declare-search")
        stencil(tenant, letters, "letterhead", "Letterhead")
        stencil(tenant, letters, "footer", "Footer")
        stencil(tenant, shared, "header", "Shared header")
        stencil(tenant, shared, "seal", "Shared seal")

        assertThat(stencils(tenant, Reach.DECLARED, "head")).containsExactly("Letterhead")
        assertThat(stencils(tenant, Reach.UNDECLARED, "head")).containsExactly("Shared header")
    }

    @Test
    fun `a catalog that can be changed here is offered, and declared, at its next version`() {
        val tenant = catalogs("declare-next")
        stencil(tenant, shared, "header", "Shared header")
        withMediator {
            MarkCatalogReady(testCatalog(tenant, shared), catalogVersion = null).execute()
            ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0", catalogVersion = null).execute()
        }
        // In its next version, not yet released: what declaring it would use.
        stencil(tenant, shared, "seal", "Shared seal")

        assertThat(stencils(tenant, Reach.UNDECLARED)).containsExactly("Shared header", "Shared seal")

        val added = declare(tenant, testCatalog(tenant, shared)).single()

        assertThat(added.releaseVersion).isNull()
        assertThat(added.draftId).isNotNull()
        assertThat(stencils(tenant, Reach.DECLARED)).contains("Shared header", "Shared seal")
    }

    @Test
    fun `the system catalog, supplied by Epistola, is offered and declared at its highest release`() {
        val tenant = catalogs("declare-system")
        withMediator { withoutSystemCatalog(testCatalog(tenant, letters)) }
        val release = withMediator { GetCatalog(systemCatalog(tenant)).query()!!.latestReleaseVersion!! }

        val offered = withMediator { ListVisibleFonts(testCatalog(tenant, letters), catalogVersion = null, reach = Reach.UNDECLARED).query() }
        assertThat(offered.map { it.version }.distinct()).containsExactly(CatalogReleaseRef(systemCatalog(tenant), DraftRef.Released(release)))

        val added = declare(tenant, systemCatalog(tenant)).single()

        assertThat(added.releaseVersion).isEqualTo(release)
        assertThat(withMediator { ListVisibleFonts(testCatalog(tenant, letters), catalogVersion = null, reach = Reach.UNDECLARED).query() }).isEmpty()
    }

    @Test
    fun `declaring a catalog declared already adds nothing`() {
        val tenant = catalogs("declare-twice")
        declare(tenant, testCatalog(tenant, shared))

        assertThat(declare(tenant, testCatalog(tenant, shared))).isEmpty()
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, letters), catalogVersion = null).query() }.map { it.catalogKey })
            .containsExactly(SYSTEM_CATALOG_KEY.value, shared.value)
    }

    @Test
    fun `images and code lists are offered the same way`() {
        val tenant = catalogs("declare-images")
        withMediator {
            UploadAsset(testCatalog(tenant, shared), "Shared logo", AssetMediaType.PNG, AssetIntegrationTest.createMinimalPng(), 1, 1, catalogVersion = null).execute()
            CreateCodeList(CodeListId(CodeListKey.of("languages"), testCatalog(tenant, shared)), "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands")), catalogVersion = null).execute()
        }
        fun images(reach: Reach) = withMediator { ListVisibleImages(testCatalog(tenant, letters), catalogVersion = null, reach = reach).query() }.map { it.name }
        fun codeLists(reach: Reach) = withMediator { ListVisibleCodeLists(testCatalog(tenant, letters), catalogVersion = null, reach = reach).query() }.map { it.displayName }

        assertThat(images(Reach.DECLARED)).doesNotContain("Shared logo")
        assertThat(images(Reach.UNDECLARED)).contains("Shared logo")
        assertThat(codeLists(Reach.DECLARED)).doesNotContain("Languages")
        assertThat(codeLists(Reach.UNDECLARED)).contains("Languages")

        declare(tenant, testCatalog(tenant, shared))

        assertThat(images(Reach.DECLARED)).contains("Shared logo")
        assertThat(codeLists(Reach.DECLARED)).contains("Languages")
        assertThat(images(Reach.UNDECLARED)).doesNotContain("Shared logo")
    }
}
