package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

private const val DEMO_CATALOG_URL = "classpath:demo/catalog/catalog.json"

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
                    "INSERT INTO document_templates (id, tenant_key, catalog_key, name, created_at, last_modified) VALUES ('stale-template', :tenantKey, :catalogKey, 'Stale', NOW(), NOW())",
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
}
