// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
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
import app.epistola.suite.testing.testCatalog
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
        val id = TemplateId(TemplateKey.of(key), testCatalog(TenantId(tenant), catalog))
        CreateDocumentTemplate(id, key.replaceFirstChar { it.uppercase() }).execute().withRequiredDataExample()
        UpdateDocumentTemplate(id, themeId = ThemeKey.of(theme), themeCatalog = testCatalog(id.tenantKey, themeFrom)).execute()
    }

    /** base (theme `house`) 1.0.0 ← shared (theme `brand`, a letterhead on `house`) 1.0.0 ← letters (an invoice on `brand`) 1.0.0. */
    private fun chain(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, base, "Base").execute()
        CreateTheme(ThemeId(ThemeKey.of("house"), testCatalog(TenantId(tenant), base)), "House").execute()
        mediator.releaseNext(testCatalog(TenantId(tenant), base))
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), shared)), "Brand").execute()
        template(tenant, shared, "letterhead", base, "house")
        mediator.releaseNext(testCatalog(TenantId(tenant), shared))
        CreateCatalog(tenant, letters, "Letters").execute()
        template(tenant, letters, "invoice", shared, "brand")
        mediator.releaseNext(testCatalog(TenantId(tenant), letters))
        tenant
    }

    private fun fat(tenant: TenantKey) = withMediator { ExportCatalogZip(testCatalog(tenant, letters), "1.0.0", includeDependencies = true).execute() }

    @Test
    fun `an export with dependencies carries the releases it pins, transitively, under dependencies, and the same manifest`() {
        val tenant = chain("bundle-export")

        val export = fat(tenant)
        val thin = withMediator { ExportCatalogZip(testCatalog(tenant, letters), "1.0.0").execute() }

        assertThat(export.filename).isEqualTo("letters-1.0.0-with-dependencies.zip")
        val namespace = withMediator { GetCatalog(testCatalog(tenant, base)).query()!! }.let { "${it.registry}/${it.namespace}" }
        assertThat(names(export.zipBytes).filter { it.startsWith("dependencies/") })
            .containsExactlyInAnyOrder("dependencies/$namespace/base/1.0.0.zip", "dependencies/$namespace/shared/1.0.0.zip")
        assertThat(names(thin.zipBytes).filter { it.startsWith("dependencies/") }).`as`("a thin export carries none").isEmpty()
        assertThat(String(entry(export.zipBytes, "catalog.json"))).`as`("the manifest does not change").isEqualTo(String(entry(thin.zipBytes, "catalog.json")))
    }

    @Test
    fun `importing a fat export installs what is missing, then the catalog, and keeps what is there`() {
        val bundle = fat(chain("bundle-source")).zipBytes
        val target = withMediator { createTenant("bundle-target").id }

        val imported = withMediator { ImportCatalogZip(target, bundle, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }

        assertThat(imported.catalogKey).isEqualTo(letters)
        assertThat(imported.installedDependencies).containsExactly("base v1.0.0", "shared v1.0.0")
        assertThat(withMediator { ListCatalogs(target).query() }.map { it.id.value to it.latestReleaseVersion })
            .contains(base.value to "1.0.0", shared.value to "1.0.0", letters.value to "1.0.0")
        assertThat(withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(target), letters))).query()!! }.themeCatalogKey)
            .isEqualTo(shared)

        val again = withMediator { ImportCatalogZip(target, bundle, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }
        assertThat(again.installedDependencies).isEmpty()
        assertThat(again.presentDependencies).containsExactly("base v1.0.0", "shared v1.0.0")
    }

    @Test
    fun `an export with dependencies installs a carried catalog beside one of the installation with the same key in another namespace`() {
        val bundle = fat(chain("bundle-beside-source")).zipBytes
        val target = withMediator {
            val target = createTenant("bundle-beside-target").id
            // Its own Shared, in its own namespace: another catalog than the one the archive carries.
            CreateCatalog(target, shared, "Our shared").execute()
            target
        }

        val imported = withMediator { ImportCatalogZip(target, bundle, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }

        // ADR 0034, section 9: within a tenant a catalog is named by namespace and key.
        assertThat(imported.installedDependencies).containsExactly("base v1.0.0", "shared v1.0.0")
        assertThat(withMediator { ListCatalogs(target).query() }.filter { it.id == shared }.map { it.name }).containsExactlyInAnyOrder("Our shared", "Shared")
        val carried = withMediator { ListCatalogs(target).query() }.single { it.id == shared && it.name == "Shared" }
        // The template's theme is the carried Shared's, which its declaration names.
        withMediator {
            val template = GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), app.epistola.suite.common.ids.CatalogId(carried.namespace, letters, TenantId(target)))).query()!!
            assertThat(template.themeCatalogId).isEqualTo(carried.catalogId)
            assertThat(GetCatalog(carried.catalogId).query()!!.latestReleaseVersion).isEqualTo("1.0.0")
        }
    }

    private fun names(zip: ByteArray): List<String> = ZipInputStream(zip.inputStream()).use { stream -> generateSequence { stream.nextEntry }.map { it.name }.toList() }

    private fun entry(zip: ByteArray, name: String): ByteArray = ZipInputStream(zip.inputStream()).use { stream ->
        generateSequence { stream.nextEntry }.first { it.name == name }
        stream.readAllBytes()
    }
}
