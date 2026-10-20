// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CatalogImportConflictException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
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
import java.util.zip.ZipInputStream

/**
 * An export with dependencies (`ExportCatalogZip` with `includeDependencies`, contract wire v8): a
 * release carrying the releases it pins, followed transitively, under `dependencies/`, so it
 * installs where none of them is; and its import, which installs what is missing first, keeps what
 * is there, and refuses rather than install into another catalog of the same key.
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
        mediator.releaseNext(CatalogId(shared, TenantId(tenant)))
        CreateCatalog(tenant, letters, "Letters").execute()
        template(tenant, letters, "invoice", shared, "brand")
        mediator.releaseNext(CatalogId(letters, TenantId(tenant)))
        tenant
    }

    private fun fat(tenant: TenantKey) = withMediator { ExportCatalogZip(tenant, letters, "1.0.0", includeDependencies = true).execute() }

    @Test
    fun `an export with dependencies carries the releases it pins, transitively, under dependencies, and the same manifest`() {
        val tenant = chain("bundle-export")

        val export = fat(tenant)
        val thin = withMediator { ExportCatalogZip(tenant, letters, "1.0.0").execute() }

        assertThat(export.filename).isEqualTo("letters-1.0.0-with-dependencies.zip")
        val namespace = withMediator { GetCatalog(tenant, base).query()!! }.let { "${it.registry}/${it.namespace}" }
        assertThat(names(export.zipBytes).filter { it.startsWith("dependencies/") })
            .containsExactlyInAnyOrder("dependencies/$namespace/base/1.0.0.zip", "dependencies/$namespace/shared/1.0.0.zip")
        assertThat(names(thin.zipBytes).filter { it.startsWith("dependencies/") }).`as`("a thin export carries none").isEmpty()
        assertThat(String(entry(export.zipBytes, "catalog.json"))).`as`("the manifest does not change").isEqualTo(String(entry(thin.zipBytes, "catalog.json")))
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
    fun `an export with dependencies that would install a catalog the installation has under another name is refused, and nothing changes`() {
        val bundle = fat(chain("bundle-conflict-source")).zipBytes
        val target = withMediator {
            val target = createTenant("bundle-conflict-target").id
            // Its own Shared, in its own namespace: another catalog than the one the archive carries.
            CreateCatalog(target, shared, "Our shared").execute()
            target
        }

        assertThatThrownBy { withMediator { ImportCatalogZip(target, bundle, CatalogType.SUBSCRIBED).execute() } }
            .isInstanceOf(CatalogImportConflictException::class.java)
            .hasMessageContaining("another catalog")
        assertThat(withMediator { GetCatalog(target, base).query() }).`as`("not even what did not conflict").isNull()
        assertThat(withMediator { GetCatalog(target, letters).query() }).isNull()
    }

    private fun names(zip: ByteArray): List<String> = ZipInputStream(zip.inputStream()).use { stream -> generateSequence { stream.nextEntry }.map { it.name }.toList() }

    private fun entry(zip: ByteArray, name: String): ByteArray = ZipInputStream(zip.inputStream()).use { stream ->
        generateSequence { stream.nextEntry }.first { it.name == name }
        stream.readAllBytes()
    }
}
