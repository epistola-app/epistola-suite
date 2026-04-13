package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

private const val DEP_TEST_CATALOG = "classpath:test-catalogs/dependency-test/catalog.json"
private const val INVALID_DEPS_CATALOG = "classpath:test-catalogs/invalid-deps/catalog.json"

/**
 * Tests that installing individual resources from a catalog automatically
 * includes their dependencies (themes, stencils, attributes, assets).
 */
class DependencyResolutionTest : IntegrationTestBase() {

    private fun registerTestCatalog(tenantKey: app.epistola.suite.common.ids.TenantKey): CatalogKey {
        val catalog = RegisterCatalog(
            tenantKey = tenantKey,
            sourceUrl = DEP_TEST_CATALOG,
            authType = AuthType.NONE,
        ).execute()
        return catalog.id
    }

    @Test
    fun `installing template with no deps installs only that template`() {
        val tenant = createTenant("Dep Test - No Deps")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("no-deps"),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].slug).isEqualTo("no-deps")
            assertThat(results[0].type).isEqualTo("template")
            assertThat(results[0].status).isNotEqualTo(InstallStatus.FAILED)
        }
    }

    @Test
    fun `installing template auto-includes referenced theme`() {
        val tenant = createTenant("Dep Test - Theme")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("full-deps"),
            ).execute()

            val types = results.map { "${it.type}:${it.slug}" }.toSet()
            assertThat(types).contains("theme:test-theme")
            assertThat(results).allMatch { it.status != InstallStatus.FAILED }
        }
    }

    @Test
    fun `installing template auto-includes referenced stencil`() {
        val tenant = createTenant("Dep Test - Stencil")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("full-deps"),
            ).execute()

            val types = results.map { "${it.type}:${it.slug}" }.toSet()
            assertThat(types).contains("stencil:header-with-logo")
        }
    }

    @Test
    fun `installing template auto-includes referenced attributes`() {
        val tenant = createTenant("Dep Test - Attributes")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("full-deps"),
            ).execute()

            val types = results.map { "${it.type}:${it.slug}" }.toSet()
            assertThat(types).contains("attribute:language")
        }
    }

    @Test
    fun `installing template auto-includes transitive asset via stencil`() {
        val tenant = createTenant("Dep Test - Transitive Asset")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            // Install only the template — it references a stencil which references an asset
            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("full-deps"),
            ).execute()

            val types = results.map { "${it.type}:${it.slug}" }.toSet()

            // Direct deps
            assertThat(types).contains("theme:test-theme")
            assertThat(types).contains("stencil:header-with-logo")
            assertThat(types).contains("attribute:language")

            // Transitive dep: stencil → asset
            assertThat(types).contains("asset:01966a00-0000-7000-8000-000000000099")

            // Plus the template itself
            assertThat(types).contains("template:full-deps")

            assertThat(results).allMatch { it.status != InstallStatus.FAILED }
        }
    }

    @Test
    fun `installing all resources works without duplicates`() {
        val tenant = createTenant("Dep Test - All")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
            ).execute()

            assertThat(results).hasSize(6) // asset, attribute, theme, stencil, 2 templates
            assertThat(results).allMatch { it.status != InstallStatus.FAILED }
        }
    }

    @Test
    fun `dependency order is correct when auto-including`() {
        val tenant = createTenant("Dep Test - Order")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
                resourceSlugs = listOf("full-deps"),
            ).execute()

            val types = results.map { it.type }
            val assetIdx = types.indexOfFirst { it == "asset" }
            val attrIdx = types.indexOfFirst { it == "attribute" }
            val themeIdx = types.indexOfFirst { it == "theme" }
            val stencilIdx = types.indexOfFirst { it == "stencil" }
            val templateIdx = types.indexOfFirst { it == "template" }

            // All should be present
            assertThat(listOf(assetIdx, attrIdx, themeIdx, stencilIdx, templateIdx)).allMatch { it >= 0 }

            // Order: asset < attribute < theme < stencil < template
            assertThat(assetIdx).isLessThan(attrIdx)
            assertThat(attrIdx).isLessThan(themeIdx)
            assertThat(themeIdx).isLessThan(stencilIdx)
            assertThat(stencilIdx).isLessThan(templateIdx)
        }
    }

    @Test
    fun `installing catalog with missing dependencies fails with clear error`() {
        val tenant = createTenant("Dep Test - Invalid")

        withMediator {
            val catalog = RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = INVALID_DEPS_CATALOG,
                authType = AuthType.NONE,
            ).execute()

            assertThatThrownBy {
                InstallFromCatalog(
                    tenantKey = tenant.id,
                    catalogKey = catalog.id,
                ).execute()
            }
                .isInstanceOf(InvalidCatalogException::class.java)
                .hasMessageContaining("asset:00000000-0000-0000-0000-missing00001")
                .hasMessageContaining("stencil:nonexistent-stencil")
                .hasMessageContaining("theme:nonexistent-theme")
                .hasMessageContaining("attribute:nonexistent-attr")
        }
    }
}
