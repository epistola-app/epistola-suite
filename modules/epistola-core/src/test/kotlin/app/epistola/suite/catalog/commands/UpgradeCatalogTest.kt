// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.io.path.writeText

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

class UpgradeCatalogTest : IntegrationTestBase() {

    private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()

    @Test
    fun `upgrade updates installed resources and version`() {
        val tenant = createTenant("Upgrade All Test")

        withMediator {
            RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            InstallFromCatalog(
                catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo")),
            ).execute()

            val result = UpgradeCatalog(
                catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo")),
            ).execute()

            assertThat(result.newVersion).isNotBlank()
            assertThat(result.installResults).isNotEmpty()
            assertThat(result.installResults.filter { it.status == InstallStatus.FAILED }).isEmpty()
        }
    }

    @Test
    fun `upgrade installs manifest resources that are not installed locally`() {
        val tenant = createTenant("Upgrade Tops Up Test")
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            // Registered but never installed: the same shape as an installation left partial by the
            // selective install this replaced (#850). An upgrade reconciles the whole manifest, so
            // it tops up rather than preserving whatever subset happens to be present.
            val result = UpgradeCatalog(
                catalog = testCatalog(tenant.id, catalogKey),
            ).execute()

            assertThat(result.installResults.map { it.slug })
                .contains("corporate", "hello-world", "advanced-data-contract")
            assertThat(result.installResults).allMatch { it.status != InstallStatus.FAILED }
        }
    }

    /** A writable copy of the dependency-test fixture at [dir], released as [version]; [edit] changes it first. */
    private fun fixture(dir: Path, version: String, edit: (Path) -> Unit = {}): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val manifest = dir.resolve("catalog.json")
        manifest.writeText(manifest.readText().replace("\"version\": \"1.0.0\"", "\"version\": \"$version\""))
        edit(dir)
        return manifest.toUri().toString()
    }

    /** Drops [key] of [type] from the manifest at [dir]: a release that no longer publishes it. */
    private fun drop(dir: Path, type: String, key: String) {
        val manifest = dir.resolve("catalog.json")
        val json = mapper.readTree(manifest.readText()) as tools.jackson.databind.node.ObjectNode
        val kept = json.get("resources").filterNot { it.get("type").asString() == type && it.get("key").asString() == key }
        json.putArray("resources").addAll(kept)
        manifest.writeText(mapper.writeValueAsString(json))
    }

    /** Points [dir]'s source at [next]: the catalog's source now offers the release copied there. */
    private fun publish(dir: Path, next: Path) {
        Files.walk(next).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { if (!Files.isDirectory(it)) Files.copy(it, dir.resolve(next.relativize(it).toString()), StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    @Test
    fun `an upgrade to a release that no longer publishes a resource removes it`(@TempDir tmp: Path) {
        val tenant = createTenant("Upgrade Drops Test")
        val depKey = CatalogKey.of("dep-test")
        val source = tmp.resolve("source")
        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = fixture(source, "1.0.0"), authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()
            fixture(tmp.resolve("next"), "1.1.0") { drop(it, "template", "no-deps") }
            publish(source, tmp.resolve("next"))

            val result = UpgradeCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            assertThat(result.aborted).isFalse()
            assertThat(result.newVersion).isEqualTo("1.1.0")
            assertThat(result.removedResources).containsExactly(app.epistola.suite.catalog.RemovedResource("template", "no-deps"))
            assertThat(GetDocumentTemplate(TemplateId(TemplateKey.of("no-deps"), testCatalog(TenantId(tenant.id), depKey))).query()).isNull()
            assertThat(GetCatalog(testCatalog(tenant.id, depKey)).query()!!.installedReleaseVersion).isEqualTo("1.1.0")
        }
    }

    @Test
    fun `an upgrade is refused when a resource the publisher dropped is used by another catalog`(@TempDir tmp: Path) {
        val tenant = createTenant("Upgrade Conflict Test")
        val tenantId = TenantId(tenant.id)
        val depKey = CatalogKey.of("dep-test")
        val source = tmp.resolve("source")
        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = fixture(source, "1.0.0"), authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()
            // A template of another catalog renders with the subscribed catalog's theme.
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), testCatalog(tenantId, CatalogKey.DEFAULT))
            CreateDocumentTemplate(id = templateId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(id = templateId, themeId = ThemeKey.of("test-theme"), themeCatalog = testCatalog(tenantId, depKey)).execute()
            // 1.1.0 no longer publishes the theme, nor uses it itself.
            fixture(tmp.resolve("next"), "1.1.0") { dir ->
                drop(dir, "theme", "test-theme")
                val template = dir.resolve("resources/templates/full-deps.json")
                template.writeText(template.readText().replace(Regex("\"themeRef\": \\{[^}]*\\}"), "\"themeRef\": { \"type\": \"inherit\" }"))
            }
            publish(source, tmp.resolve("next"))

            assertThatThrownBy { UpgradeCatalog(catalog = testCatalog(tenant.id, depKey)).execute() }
                .isInstanceOf(CatalogUpgradeConflictException::class.java)
                .hasMessageContaining("test-theme")
                .hasMessageContaining("Cross-Ref Template")
            assertThat(GetCatalog(testCatalog(tenant.id, depKey)).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `an upgrade of a catalog with changes of its own records the release beside its next version`(@TempDir tmp: Path) {
        val tenant = createTenant("Upgrade Beside Test")
        val tenantId = TenantId(tenant.id)
        val depKey = CatalogKey.of("dep-test")
        val source = tmp.resolve("source")
        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = fixture(source, "1.0.0"), authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()
            CatalogImportContext.runAsImport {
                UpdateDocumentTemplate(id = TemplateId(TemplateKey.of("no-deps"), testCatalog(tenantId, depKey)), name = "Ours").execute()
            }
            fixture(tmp.resolve("next"), "1.1.0") { drop(it, "template", "no-deps") }
            publish(source, tmp.resolve("next"))

            val result = UpgradeCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            // Received beside the next version, to be merged (ADR 0032): nothing of it is removed.
            assertThat(result.aborted).isFalse()
            assertThat(result.removedResources).isEmpty()
            assertThat(GetDocumentTemplate(TemplateId(TemplateKey.of("no-deps"), testCatalog(tenantId, depKey))).query()!!.name).isEqualTo("Ours")
            assertThat(ListCatalogReleaseSummaries(testCatalog(tenant.id, depKey)).query().map { it.version }).contains("1.0.0", "1.1.0")
            assertThat(GetCatalog(testCatalog(tenant.id, depKey)).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
        }
    }

    @Test
    fun `an upgrade whose source cannot serve a binary is refused and changes nothing`(@TempDir tmp: Path) {
        val tenant = createTenant("Upgrade Abort Test")
        val depKey = CatalogKey.of("dep-test")
        val source = tmp.resolve("source")
        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = fixture(source, "1.0.0"), authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()
            fixture(tmp.resolve("next"), "1.1.0") { drop(it, "template", "no-deps") }
            publish(source, tmp.resolve("next"))
            Files.delete(source.resolve("binaries/test-logo.png"))

            // A catalog is one install unit: the release is fetched whole before anything is written.
            assertThatThrownBy { UpgradeCatalog(catalog = testCatalog(tenant.id, depKey)).execute() }
                .isInstanceOfAny(app.epistola.suite.catalog.outbound.OutboundUrlRejectedException::class.java, app.epistola.suite.catalog.sources.CatalogSourceUnavailableException::class.java)
            assertThat(GetCatalog(testCatalog(tenant.id, depKey)).query()!!.installedReleaseVersion).isEqualTo("1.0.0")
            assertThat(GetDocumentTemplate(TemplateId(TemplateKey.of("no-deps"), testCatalog(TenantId(tenant.id), depKey))).query()).isNotNull()
        }
    }
}
