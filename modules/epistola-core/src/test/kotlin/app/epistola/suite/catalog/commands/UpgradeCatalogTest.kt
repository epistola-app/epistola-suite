// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.toPath

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"

class UpgradeCatalogTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

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

            // Manually insert a fake template that is not in the manifest
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    "INSERT INTO document_templates (id, tenant_key, catalog_key, name, created_at, updated_at) VALUES ('stale-template', :tenantKey, :catalogKey, 'Stale', NOW(), NOW())",
                )
                    .bind("tenantKey", tenant.id)
                    .bind("catalogKey", catalogKey)
                    .execute()
            }

            val result = UpgradeCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).execute()

            assertThat(result.removedResources).anyMatch { it.slug == "stale-template" }

            val templateId = TemplateId(
                TemplateKey.of("stale-template"),
                CatalogId(catalogKey, TenantId(tenant.id)),
            )
            val staleTemplate = GetDocumentTemplate(templateId).query()
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

            // Manually insert a stale theme that is NOT in the manifest but IS referenced
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    INSERT INTO themes (id, tenant_key, catalog_key, name, created_at, updated_at)
                    VALUES ('stale-theme', :t, :c, 'Stale Theme', NOW(), NOW())
                    """,
                ).bind("t", tenant.id).bind("c", catalogKey).execute()
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
