// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CatalogBundleException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.dependencies.DeclareUsedCatalogs
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * A fat export (`ExportCatalogZip` with `includeDependencies`): a release with the releases of the
 * catalogs it uses, followed transitively, so it installs where none of them is; and its import,
 * which installs what is missing, keeps what is there, and refuses rather than move a catalog
 * something else uses.
 */
class CatalogBundleTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val base = CatalogKey.of("base")
    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    private fun template(tenant: TenantKey, catalog: CatalogKey, key: String, themeFrom: CatalogKey, theme: String) {
        val id = TemplateId(TemplateKey.of(key), CatalogId(catalog, TenantId(tenant)))
        CreateDocumentTemplate(id, key.replaceFirstChar { it.uppercase() }).execute().withRequiredDataExample()
        UpdateDocumentTemplate(id, themeId = ThemeKey.of(theme), themeCatalogKey = themeFrom).execute()
    }

    /** base (theme `house`) 1.0.0 ← shared (theme `brand`, a letterhead on `house`) 1.0.0 ← letters (an invoice on `brand`) 1.0.0. */
    private fun chain(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, base, "Base").execute()
        CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(base, TenantId(tenant))), "House").execute()
        mediator.releaseNext(CatalogId(base, TenantId(tenant)))
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), "Brand").execute()
        template(tenant, shared, "letterhead", base, "house")
        DeclareUsedCatalogs(tenant, shared).execute()
        mediator.releaseNext(CatalogId(shared, TenantId(tenant)))
        CreateCatalog(tenant, letters, "Letters").execute()
        template(tenant, letters, "invoice", shared, "brand")
        DeclareUsedCatalogs(tenant, letters).execute()
        mediator.releaseNext(CatalogId(letters, TenantId(tenant)))
        tenant
    }

    private fun fat(tenant: TenantKey) = withMediator { ExportCatalogZip(tenant, letters, "1.0.0", includeDependencies = true).execute() }

    @Test
    fun `a fat export holds the release and what it uses, transitively, each before what uses it`() {
        val tenant = chain("bundle-export")

        val export = fat(tenant)

        assertThat(export.filename).isEqualTo("letters-1.0.0-with-dependencies.zip")
        assertThat(CatalogBundle.isBundle(export.zipBytes)).isTrue()
        val bundle = CatalogBundle.read(export.zipBytes, objectMapper, 50_000_000)
        assertThat(bundle.entries.map { "${it.catalogKey}@${it.version}" }).containsExactly("base@1.0.0", "shared@1.0.0", "letters@1.0.0")
        // Each is the archive a single export writes.
        assertThat(bundle.dependencies.first().archive).isEqualTo(withMediator { ExportCatalogZip(tenant, base, "1.0.0").execute() }.zipBytes)
        assertThat(CatalogBundle.isBundle(withMediator { ExportCatalogZip(tenant, letters, "1.0.0").execute() }.zipBytes)).`as`("without it, one archive").isFalse()
    }

    @Test
    fun `importing a fat export installs what is missing, then the catalog, and keeps what is there`() {
        val bundle = fat(chain("bundle-source")).zipBytes
        val target = withMediator { createTenant("bundle-target").id }

        val imported = withMediator { ImportCatalogZip(target, bundle, CatalogType.SUBSCRIBED).execute() }

        assertThat(imported.catalogKey).isEqualTo(letters)
        assertThat(imported.installedDependencies).containsExactly("base v1.0.0", "shared v1.0.0")
        assertThat(withMediator { ListCatalogs(target).query() }.filter { it.type == CatalogType.SUBSCRIBED }.map { it.id.value to it.installedReleaseVersion })
            .contains(base.value to "1.0.0", shared.value to "1.0.0", letters.value to "1.0.0")
        assertThat(withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(target)))).query()!! }.themeCatalogKey)
            .isEqualTo(shared)

        val again = withMediator { ImportCatalogZip(target, bundle, CatalogType.SUBSCRIBED).execute() }
        assertThat(again.installedDependencies).isEmpty()
        assertThat(again.presentDependencies).containsExactly("base v1.0.0", "shared v1.0.0")
    }

    @Test
    fun `a fat export that would move a catalog the installation has otherwise is refused, and nothing changes`() {
        val bundle = fat(chain("bundle-conflict-source")).zipBytes
        val target = withMediator {
            val target = createTenant("bundle-conflict-target").id
            // Its own Shared, which is not the one the bundle carries.
            CreateCatalog(target, shared, "Our shared").execute()
            target
        }

        assertThatThrownBy { withMediator { ImportCatalogZip(target, bundle, CatalogType.SUBSCRIBED).execute() } }
            .isInstanceOf(CatalogBundleException::class.java)
            .hasMessageContaining("shared here is")
        assertThat(withMediator { GetCatalog(target, base).query() }).`as`("not even what did not conflict").isNull()
        assertThat(withMediator { GetCatalog(target, letters).query() }).isNull()
    }

    @Test
    fun `the working copy is bundled with what it is tied to, and refused while something is not tied yet`() {
        val tenant = chain("bundle-working-copy")
        val workingCopy = withMediator { ExportCatalogZip(tenant, letters, includeDependencies = true).execute() }
        assertThat(CatalogBundle.read(workingCopy.zipBytes, objectMapper, 50_000_000).dependencies.map { it.catalogKey }).containsExactly("base", "shared")

        val unpublished = CatalogKey.of("unpublished")
        withMediator {
            CreateCatalog(tenant, unpublished, "Unpublished").execute()
            CreateTheme(ThemeId(ThemeKey.of("draft"), CatalogId(unpublished, TenantId(tenant))), "Draft").execute()
            template(tenant, letters, "reminder", unpublished, "draft")
            DeclareUsedCatalogs(tenant, letters).execute()
        }
        assertThatThrownBy { withMediator { ExportCatalogZip(tenant, letters, includeDependencies = true).execute() } }
            .isInstanceOf(CatalogBundleException::class.java)
            .hasMessageContaining("unpublished, which is not tied to a release yet")
    }
}
