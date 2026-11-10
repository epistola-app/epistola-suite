// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.fonts.queries.ListVisibleFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A new catalog can use the system catalog from the start: it declares it at its highest release,
 * and its fonts are served from that release. What a version can use is in [VisibleResourcesTest].
 */
class SystemCatalogDeclarationTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

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
    fun `a font face is read at the release a catalog declares`() {
        val tenant = createTenant("face-at-release").id
        val visible = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            ListVisibleFonts(testCatalog(tenant, letters), catalogVersion = null).query().first { it.font.slug.value == "inter" }
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
}
