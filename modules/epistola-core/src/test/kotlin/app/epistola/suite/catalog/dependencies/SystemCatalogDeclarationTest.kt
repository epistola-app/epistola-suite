// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListVisibleCatalogs
import app.epistola.suite.catalog.queries.VisibleCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every catalog can use the system catalog from the start: a new catalog declares it at its highest
 * release, and what a version can use is its own catalog and the catalogs it declares, each at the
 * version its declaration names.
 */
class SystemCatalogDeclarationTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")
    private val other = CatalogKey.of("other")

    private fun systemRelease(tenant: TenantKey): String = withMediator { GetCatalog(systemCatalog(tenant)).query()!!.latestReleaseVersion!! }

    private fun declarations(tenant: TenantKey, key: CatalogKey) = withMediator { ListCatalogDependencies(testCatalog(tenant, key), catalogVersion = null).query() }

    private fun systemDeclarations(tenant: TenantKey, key: CatalogKey) = declarations(tenant, key).filter { it.namespace == "epistola" && it.catalogKey == SYSTEM_CATALOG_KEY.value }

    @Test
    fun `a new catalog declares the system catalog at its highest release`() {
        val tenant = createTenant("system-declared").id
        withMediator { CreateCatalog(tenant, letters, "Letters").execute() }

        val declared = systemDeclarations(tenant, letters).single()

        assertThat(declared.releaseVersion).isEqualTo(systemRelease(tenant))
        assertThat(declared.releaseFingerprint).isNotNull()
    }

    @Test
    fun `a version sees its own catalog first, then each catalog it declares at the version the declaration names`() {
        val tenant = createTenant("visible-catalogs").id
        val (draftKey, visible) = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateCatalog(tenant, other, "Other").execute()
            MarkCatalogReady(testCatalog(tenant, shared), catalogVersion = null).execute()
            ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0", catalogVersion = null).execute()
            val draft = CreateCatalogDraft(testCatalog(tenant, other), "Redesign").execute().versionId
            val sharedCatalog = GetCatalog(testCatalog(tenant, shared)).query()!!
            val otherCatalog = GetCatalog(testCatalog(tenant, other)).query()!!
            val system = systemDeclarations(tenant, letters).single()
            UpdateCatalogDependencies(
                testCatalog(tenant, letters),
                listOf(
                    DeclaredCatalogDependency(system.registry, system.namespace, system.catalogKey, system.releaseVersion),
                    DeclaredCatalogDependency(sharedCatalog.registry, sharedCatalog.namespace, shared.value, releaseVersion = "1.0.0"),
                    DeclaredCatalogDependency(otherCatalog.registry, otherCatalog.namespace, other.value, versionId = draft),
                ),
                catalogVersion = null,
            ).execute()
            draft to ListVisibleCatalogs(testCatalog(tenant, letters), catalogVersion = null).query().catalogs
        }

        assertThat(visible).containsExactly(
            VisibleCatalog(testCatalog(tenant, letters), null),
            VisibleCatalog(systemCatalog(tenant), CatalogReleaseRef(systemCatalog(tenant), DraftRef.Released(systemRelease(tenant)))),
            VisibleCatalog(testCatalog(tenant, shared), CatalogReleaseRef(testCatalog(tenant, shared), DraftRef.Released("1.0.0"))),
            VisibleCatalog(testCatalog(tenant, other), CatalogReleaseRef(testCatalog(tenant, other), DraftRef.Id(draftKey))),
        )
    }

    @Test
    fun `a font face is read at the release a catalog declares`() {
        val tenant = createTenant("face-at-release").id
        val visible = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            ListVisibleCatalogs(testCatalog(tenant, letters), catalogVersion = null).query().catalogs.single { it.catalog == systemCatalog(tenant) }
        }

        val bytes = withMediator { GetFontVariantContent(systemCatalog(tenant), FontKey.of("inter"), 400, false, catalogVersion = visible.version).query() }

        assertThat(visible.version?.draft).isEqualTo(DraftRef.Released(systemRelease(tenant)))
        assertThat(bytes).isNotNull().hasSizeGreaterThan(10_000)
    }

    @Test
    fun `an unused system catalog declaration may be removed, but is no leftover to point out`() {
        val tenant = createTenant("system-not-left-over").id
        val borrowing = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            GetCatalogBorrowing(testCatalog(tenant, letters), catalogVersion = null).query()
        }

        assertThat(borrowing.unused.map { it.catalogKey }).containsExactly(SYSTEM_CATALOG_KEY.value)
        assertThat(borrowing.leftOver).isEmpty()
    }

    @Test
    fun `a catalog it does not declare is not visible`() {
        val tenant = createTenant("not-visible").id
        val visible = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
            ListVisibleCatalogs(testCatalog(tenant, letters), catalogVersion = null).query().catalogs.map { it.catalog }
        }

        assertThat(visible).containsExactly(testCatalog(tenant, letters), systemCatalog(tenant))
    }
}
