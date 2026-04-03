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
import app.epistola.suite.testing.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

private const val DEMO_CATALOG_URL = "classpath:demo/catalog/catalog.json"

@SpringBootTest(
    classes = [TestApplication::class],
    properties = ["epistola.demo.enabled=false"],
)
@ActiveProfiles("test")
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
            assertThat(catalog.type).isEqualTo(CatalogType.IMPORTED)
            assertThat(catalog.sourceUrl).isEqualTo(DEMO_CATALOG_URL)
            assertThat(catalog.installedReleaseVersion).isEqualTo("1.0")
        }
    }

    @Test
    fun `list catalogs returns registered catalogs`() {
        val tenant = createTenant("List Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val catalogs = ListCatalogs(tenant.id).query()
            assertThat(catalogs).hasSize(1)
            assertThat(catalogs[0].name).isEqualTo("Epistola Demo Catalog")
        }
    }

    @Test
    fun `browse catalog shows available resources`() {
        val tenant = createTenant("Browse Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val result = BrowseCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).query()

            assertThat(result.resources).hasSize(2)
            assertThat(result.resources.map { it.slug }).containsExactlyInAnyOrder("hello-world", "simple-letter")
            assertThat(result.resources).allMatch { it.status == ResourceStatus.AVAILABLE }
        }
    }

    @Test
    fun `install from catalog creates templates`() {
        val tenant = createTenant("Install Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            assertThat(results).hasSize(2)
            assertThat(results).allMatch { it.status == InstallStatus.INSTALLED }

            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates.map { it.id.value }).containsExactlyInAnyOrder("hello-world", "simple-letter")
        }
    }

    @Test
    fun `install selective slug only installs that template`() {
        val tenant = createTenant("Selective Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
                resourceSlugs = listOf("hello-world"),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].slug).isEqualTo("hello-world")
            assertThat(results[0].status).isEqualTo(InstallStatus.INSTALLED)

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
    fun `unregister catalog removes catalog but keeps templates`() {
        val tenant = createTenant("Unregister Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            UnregisterCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            val catalog = GetCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).query()
            assertThat(catalog).isNull()

            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates).hasSize(2)
        }
    }

    @Test
    fun `reinstall after unregister updates existing templates`() {
        val tenant = createTenant("Reinstall Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            UnregisterCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            val results = InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            assertThat(results).hasSize(2)
            assertThat(results).allMatch { it.status == InstallStatus.UPDATED }
        }
    }
}
