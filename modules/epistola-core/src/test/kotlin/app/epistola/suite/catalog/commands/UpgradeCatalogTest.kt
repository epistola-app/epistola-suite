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
import kotlin.io.path.toPath

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

class UpgradeCatalogTest : IntegrationTestBase() {

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
    fun `upgrade removes stale resources`() {
        val tenant = createTenant("Upgrade Stale Test")
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            InstallFromCatalog(
                catalog = testCatalog(tenant.id, catalogKey),
            ).execute()

            val staleTemplateId = TemplateId(
                TemplateKey.of("stale-template"),
                testCatalog(TenantId(tenant.id), catalogKey),
            )
            CatalogImportContext.runAsImport {
                CreateDocumentTemplate(id = staleTemplateId, name = "Stale").execute()
            }

            val result = UpgradeCatalog(
                catalog = testCatalog(tenant.id, catalogKey),
            ).execute()

            assertThat(result.removedResources).anyMatch { it.slug == "stale-template" }

            val staleTemplate = GetDocumentTemplate(staleTemplateId).query()
            assertThat(staleTemplate).isNull()
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

    @Test
    fun `upgrade removes stale code lists and fonts`() {
        val tenant = createTenant("Upgrade All Types Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()
            ImportCodeList(testCatalog(tenantId, catalogKey), "stale-list", "Stale List").execute()
            // Fonts reach an installed catalog only through a catalog import (#1023).
            CatalogImportContext.runAsImport { ImportFont(testCatalog(tenantId, catalogKey), "stale-font", "Stale Font", "sans").execute() }

            val result = UpgradeCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()

            assertThat(result.removedResources)
                .contains(
                    app.epistola.suite.catalog.RemovedResource("codeList", "stale-list"),
                    app.epistola.suite.catalog.RemovedResource("font", "stale-font"),
                )
            assertThat(ListCodeLists(tenantId, testCatalog(tenantId, catalogKey)).query().map { it.slug.value }).doesNotContain("stale-list")
            assertThat(ListFonts(tenantId, testCatalog(tenantId, catalogKey)).query().map { it.slug.value }).doesNotContain("stale-font")
        }
    }

    @Test
    fun `upgrade protects stale code lists and fonts referenced outside their catalog`() {
        val tenant = createTenant("Upgrade Reference Types Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("epistola-demo")
        val subscribedCatalog = testCatalog(tenantId, catalogKey)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()
            ImportCodeList(testCatalog(tenantId, catalogKey), "used-list", "Used List").execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("uses-list"), CatalogId.default(tenantId)),
                displayName = "Uses List",
                codeListId = CodeListId(CodeListKey.of("used-list"), subscribedCatalog),
            ).execute()
            // Fonts reach an installed catalog only through a catalog import (#1023).
            CatalogImportContext.runAsImport { ImportFont(testCatalog(tenantId, catalogKey), "used-font", "Used Font", "sans").execute() }
            CreateTheme(
                id = ThemeId(ThemeKey.of("uses-font"), CatalogId.default(tenantId)),
                name = "Uses Font",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("key" to "used-font", "catalogKey" to "epistola-demo"),
                ),
            ).execute()

            assertThatThrownBy {
                UpgradeCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()
            }
                .isInstanceOf(CatalogUpgradeConflictException::class.java)
                .hasMessageContaining("Code list 'used-list'")
                .hasMessageContaining("Font 'used-font'")
        }
    }

    @Test
    fun `upgrade removes stale reference resources with their stale dependents`() {
        val tenant = createTenant("Upgrade Stale Dependency Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()
            ImportCodeList(testCatalog(tenantId, catalogKey), "obsolete-list", "Obsolete List").execute()
            ImportAttribute(
                catalog = testCatalog(tenantId, catalogKey),
                slug = "obsolete-attribute",
                displayName = "Obsolete Attribute",
                codeListCatalog = testCatalog(testCatalog(tenantId, catalogKey).tenantKey, catalogKey),
                codeListSlug = CodeListKey.of("obsolete-list"),
            ).execute()
            // Fonts reach an installed catalog only through a catalog import (#1023).
            CatalogImportContext.runAsImport { ImportFont(testCatalog(tenantId, catalogKey), "obsolete-font", "Obsolete Font", "sans").execute() }
            ImportTheme(
                catalog = testCatalog(tenantId, catalogKey),
                slug = "obsolete-theme",
                name = "Obsolete Theme",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("key" to "obsolete-font", "catalogKey" to "epistola-demo"),
                ),
            ).execute()

            val result = UpgradeCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()

            assertThat(result.removedResources.map { it.type to it.slug })
                .contains(
                    "attribute" to "obsolete-attribute",
                    "codeList" to "obsolete-list",
                    "theme" to "obsolete-theme",
                    "font" to "obsolete-font",
                )
        }
    }

    @Test
    fun `upgrade is rejected when removing a theme referenced by another catalog`() {
        val tenant = createTenant("Upgrade Conflict Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("epistola-demo")
        val defaultCatalogId = testCatalog(tenantId, CatalogKey.DEFAULT)

        withMediator {
            // Install demo catalog with all resources (includes "corporate" theme)
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()

            // Create a template in the default catalog that references the demo catalog's theme
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, defaultCatalogId)
            CreateDocumentTemplate(id = templateId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(
                id = templateId,
                themeId = app.epistola.suite.common.ids.ThemeKey.of("corporate"),
                themeCatalog = testCatalog(templateId.tenantKey, catalogKey),
            ).execute()

            CatalogImportContext.runAsImport {
                CreateTheme(
                    id = ThemeId(ThemeKey.of("stale-theme"), testCatalog(tenantId, catalogKey)),
                    name = "Stale Theme",
                ).execute()
            }

            // Point the cross-ref template at the stale theme
            UpdateDocumentTemplate(
                id = templateId,
                themeId = app.epistola.suite.common.ids.ThemeKey.of("stale-theme"),
                themeCatalog = testCatalog(templateId.tenantKey, catalogKey),
            ).execute()

            // Upgrade should be rejected because stale-theme is referenced
            assertThatThrownBy {
                UpgradeCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()
            }
                .isInstanceOf(CatalogUpgradeConflictException::class.java)
                .hasMessageContaining("stale-theme")
                .hasMessageContaining("Cross-Ref Template")
        }
    }

    @Test
    fun `upgrade aborts on failed install — version not bumped, stale not removed`(@TempDir tmp: Path) {
        // Mutable file:// copy of a self-contained fixture so we can break a
        // resource between install and upgrade.
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = tmp.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val sourceUrl = tmp.resolve("catalog.json").toUri().toString()
        val tenant = createTenant("Upgrade Abort Test")
        val depKey = CatalogKey.of("dep-test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            val install = InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()
            assertThat(install.filter { it.status == InstallStatus.FAILED }).isEmpty()
            val before = GetCatalog(testCatalog(tenant.id, depKey)).query()!!

            // Break the asset binary so its re-install on upgrade FAILS.
            Files.delete(tmp.resolve("binaries/test-logo.png"))

            val result = UpgradeCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            assertThat(result.aborted).isTrue()
            assertThat(result.installResults.any { it.status == InstallStatus.FAILED }).isTrue()
            assertThat(result.removedResources).isEmpty()

            // Version/fingerprint untouched → next run retries (no silent half-upgrade).
            val after = GetCatalog(testCatalog(tenant.id, depKey)).query()!!
            assertThat(after.installedReleaseVersion).isEqualTo(before.installedReleaseVersion)
            assertThat(after.installedFingerprint).isEqualTo(before.installedFingerprint)
            // Nothing was installed, so the install stamp must not move either.
            assertThat(after.installedAt).isEqualTo(before.installedAt)
        }
    }
}
