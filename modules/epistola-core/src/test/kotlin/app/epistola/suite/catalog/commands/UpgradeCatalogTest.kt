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
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.toPath

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"

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
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            val result = UpgradeCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
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
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).execute()

            val staleTemplateId = TemplateId(
                TemplateKey.of("stale-template"),
                CatalogId(catalogKey, TenantId(tenant.id)),
            )
            CatalogImportContext.runAsImport {
                CreateDocumentTemplate(id = staleTemplateId, name = "Stale").execute()
            }

            val result = UpgradeCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).execute()

            assertThat(result.removedResources).anyMatch { it.slug == "stale-template" }

            val staleTemplate = GetDocumentTemplate(staleTemplateId).query()
            assertThat(staleTemplate).isNull()
        }
    }

    @Test
    fun `upgrade only upgrades previously installed resources`() {
        val tenant = createTenant("Upgrade Selective Test")
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            // Install only a specific resource (corporate theme)
            InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("corporate"),
            ).execute()

            val result = UpgradeCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).execute()

            // Only previously installed resources should be upgraded
            val upgradedSlugs = result.installResults.map { it.slug }.toSet()
            assertThat(upgradedSlugs).contains("corporate")
            assertThat(upgradedSlugs).doesNotContain("hello-world", "simple-letter", "demo-invoice")
        }
    }

    @Test
    fun `full upgrade installs every manifest resource`() {
        val tenant = createTenant("Upgrade Full Test")
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("corporate"),
            ).execute()

            val result = UpgradeCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                mode = CatalogUpgradeMode.FULL,
            ).execute()

            assertThat(result.installResults.map { it.slug })
                .contains("corporate", "hello-world", "advanced-data-contract")
        }
    }

    @Test
    fun `upgrade removes stale code lists and fonts`() {
        val tenant = createTenant("Upgrade All Types Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()
            ImportCodeList(tenantId, catalogKey, "stale-list", "Stale List").execute()
            ImportFont(tenantId, catalogKey, "stale-font", "Stale Font", "sans").execute()

            val result = UpgradeCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

            assertThat(result.removedResources)
                .contains(
                    app.epistola.suite.catalog.RemovedResource("codeList", "stale-list"),
                    app.epistola.suite.catalog.RemovedResource("font", "stale-font"),
                )
            assertThat(ListCodeLists(tenantId, catalogKey).query().map { it.slug.value }).doesNotContain("stale-list")
            assertThat(ListFonts(tenantId, catalogKey).query().map { it.slug.value }).doesNotContain("stale-font")
        }
    }

    @Test
    fun `upgrade protects stale code lists and fonts referenced outside their catalog`() {
        val tenant = createTenant("Upgrade Reference Types Test")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("epistola-demo")
        val subscribedCatalog = CatalogId(catalogKey, tenantId)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()
            ImportCodeList(tenantId, catalogKey, "used-list", "Used List").execute()
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("uses-list"), CatalogId.default(tenantId)),
                displayName = "Uses List",
                codeListId = CodeListId(CodeListKey.of("used-list"), subscribedCatalog),
            ).execute()
            ImportFont(tenantId, catalogKey, "used-font", "Used Font", "sans").execute()
            CreateTheme(
                id = ThemeId(ThemeKey.of("uses-font"), CatalogId.default(tenantId)),
                name = "Uses Font",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("slug" to "used-font", "catalogKey" to "epistola-demo"),
                ),
            ).execute()

            assertThatThrownBy {
                UpgradeCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()
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
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()
            ImportCodeList(tenantId, catalogKey, "obsolete-list", "Obsolete List").execute()
            ImportAttribute(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "obsolete-attribute",
                displayName = "Obsolete Attribute",
                codeListCatalogKey = catalogKey,
                codeListSlug = CodeListKey.of("obsolete-list"),
            ).execute()
            ImportFont(tenantId, catalogKey, "obsolete-font", "Obsolete Font", "sans").execute()
            ImportTheme(
                tenantId = tenantId,
                catalogKey = catalogKey,
                slug = "obsolete-theme",
                name = "Obsolete Theme",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("slug" to "obsolete-font", "catalogKey" to "epistola-demo"),
                ),
            ).execute()

            val result = UpgradeCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

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
        val defaultCatalogId = CatalogId(CatalogKey.DEFAULT, tenantId)

        withMediator {
            // Install demo catalog with all resources (includes "corporate" theme)
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

            // Create a template in the default catalog that references the demo catalog's theme
            val templateKey = TestIdHelpers.nextTemplateId()
            val templateId = TemplateId(templateKey, defaultCatalogId)
            CreateDocumentTemplate(id = templateId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(
                id = templateId,
                themeId = app.epistola.suite.common.ids.ThemeKey.of("corporate"),
                themeCatalogKey = catalogKey,
            ).execute()

            CatalogImportContext.runAsImport {
                CreateTheme(
                    id = ThemeId(ThemeKey.of("stale-theme"), CatalogId(catalogKey, tenantId)),
                    name = "Stale Theme",
                ).execute()
            }

            // Point the cross-ref template at the stale theme
            UpdateDocumentTemplate(
                id = templateId,
                themeId = app.epistola.suite.common.ids.ThemeKey.of("stale-theme"),
                themeCatalogKey = catalogKey,
            ).execute()

            // Upgrade should be rejected because stale-theme is referenced
            assertThatThrownBy {
                UpgradeCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()
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
        val src = javaClass.classLoader.getResource("test-catalogs/dependency-test/catalog.json")!!.toURI().toPath().parent
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
            val install = InstallFromCatalog(tenantKey = tenant.id, catalogKey = depKey).execute()
            assertThat(install.filter { it.status == InstallStatus.FAILED }).isEmpty()
            val before = GetCatalog(tenant.id, depKey).query()!!

            // Break the asset binary so its re-install on upgrade FAILS.
            Files.delete(tmp.resolve("binaries/test-logo.png"))

            val result = UpgradeCatalog(tenantKey = tenant.id, catalogKey = depKey).execute()

            assertThat(result.aborted).isTrue()
            assertThat(result.installResults.any { it.status == InstallStatus.FAILED }).isTrue()
            assertThat(result.removedResources).isEmpty()

            // Version/fingerprint untouched → next run retries (no silent half-upgrade).
            val after = GetCatalog(tenant.id, depKey).query()!!
            assertThat(after.installedReleaseVersion).isEqualTo(before.installedReleaseVersion)
            assertThat(after.installedFingerprint).isEqualTo(before.installedFingerprint)
        }
    }
}
