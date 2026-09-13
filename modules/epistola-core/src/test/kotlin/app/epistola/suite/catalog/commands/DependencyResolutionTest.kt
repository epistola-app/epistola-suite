// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    fun `installing a catalog installs every resource in its manifest`() {
        val tenant = createTenant("Dep Test - Whole Catalog")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

            // Every manifest resource, not only the ones some template happens to reference. The
            // fixture's `no-deps` template needs none of the asset, attribute, theme or stencil;
            // they arrive because the catalog is the install unit (#850).
            assertThat(results.map { "${it.type}:${it.slug}" })
                .contains(
                    "template:no-deps",
                    "template:full-deps",
                    "theme:test-theme",
                    "stencil:header-with-logo",
                    "attribute:language",
                    "asset:01966a00-0000-7000-8000-000000000099",
                )
            assertThat(results).allMatch { it.status != InstallStatus.FAILED }
        }
    }

    @Test
    fun `installing a catalog produces one result per resource, no duplicates`() {
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
    fun `resources are installed in dependency order`() {
        val tenant = createTenant("Dep Test - Order")

        withMediator {
            val catalogKey = registerTestCatalog(tenant.id)

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = catalogKey,
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
