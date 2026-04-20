package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ResourceStatus
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val DEMO_CATALOG_URL = "classpath:demo/catalog/catalog.json"

class CatalogIntegrationTest : IntegrationTestBase() {

    @Test
    fun `register catalog from classpath creates catalog entity`() {
        val tenant = createTenant("Register Test")

        withMediator {
            val catalog = RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            assertThat(catalog.id).isEqualTo(CatalogKey.of("epistola-demo"))
            assertThat(catalog.name).isEqualTo("Epistola Demo Catalog")
            assertThat(catalog.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(catalog.sourceUrl).isEqualTo(DEMO_CATALOG_URL)
            assertThat(catalog.installedReleaseVersion).isEqualTo("4.8")
        }
    }

    @Test
    fun `list catalogs returns registered catalogs`() {
        val tenant = createTenant("List Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val catalogs = ListCatalogs(tenant.id).query()
            assertThat(catalogs).hasSize(2) // default + registered
            assertThat(catalogs.map { it.name }).contains("Epistola Demo Catalog")
        }
    }

    @Test
    fun `browse catalog shows available resources of all types`() {
        val tenant = createTenant("Browse Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val result = BrowseCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).query()

            assertThat(result.resources).hasSize(7)
            assertThat(result.resources.map { it.type }).containsAll(listOf("template", "theme", "stencil", "attribute"))
            assertThat(result.resources).allMatch { it.status == ResourceStatus.AVAILABLE }
        }
    }

    @Test
    fun `install from catalog creates all resource types`() {
        val tenant = createTenant("Install Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            assertThat(results).hasSize(7)
            val successful = results.filter { it.status != InstallStatus.FAILED }
            assertThat(successful).hasSize(7)

            // Verify templates were created
            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates.map { it.id.value }).containsExactlyInAnyOrder("hello-world", "simple-letter", "demo-invoice")

            // Verify resource type distribution
            assertThat(results.map { it.type }).containsAll(listOf("template", "theme", "stencil", "attribute", "asset"))
        }
    }

    @Test
    fun `install selective slug auto-includes dependencies`() {
        val tenant = createTenant("Selective Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
                resourceSlugs = listOf("hello-world"),
            ).execute()

            // hello-world references corporate theme and company-header stencil
            val types = results.map { "${it.type}:${it.slug}" }.toSet()
            assertThat(types).contains("template:hello-world", "theme:corporate", "stencil:company-header")
            assertThat(results).allMatch { it.status != InstallStatus.FAILED }

            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates).hasSize(1)
            assertThat(templates[0].id.value).isEqualTo("hello-world")
        }
    }

    @Test
    fun `browse after install shows installed status`() {
        val tenant = createTenant("Status Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            val result = BrowseCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).query()

            assertThat(result.resources).allMatch { it.status == ResourceStatus.INSTALLED }
        }
    }

    @Test
    fun `unregister catalog removes catalog and its resources`() {
        val tenant = createTenant("Unregister Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            UnregisterCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            val catalog = GetCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).query()
            assertThat(catalog).isNull()

            // Resources are deleted via CASCADE when catalog is removed
            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `reinstall after unregister creates fresh resources`() {
        val tenant = createTenant("Reinstall Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            UnregisterCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            // Re-register and install — resources were deleted by CASCADE, so this is a fresh install
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            val results = InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            assertThat(results).hasSize(7)
            assertThat(results).allMatch { it.status == InstallStatus.INSTALLED }
        }
    }

    @Test
    fun `install order respects dependencies`() {
        val tenant = createTenant("Order Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            // Verify install order: attribute → theme → stencil → template
            val types = results.map { it.type }
            val attrIdx = types.indexOf("attribute")
            val themeIdx = types.indexOf("theme")
            val stencilIdx = types.indexOf("stencil")
            val templateIdx = types.indexOf("template")

            assertThat(attrIdx).isLessThan(themeIdx)
            assertThat(themeIdx).isLessThan(stencilIdx)
            assertThat(stencilIdx).isLessThan(templateIdx)
        }
    }
}
