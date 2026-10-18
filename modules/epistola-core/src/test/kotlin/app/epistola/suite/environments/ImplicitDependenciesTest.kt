// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListImplicitDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Deploying a catalog uses the versions of other catalogs it is pinned to implicitly (contract ADR
 * 0003): they render from the tenant's copy, so the environment need not deploy them, and says it
 * uses them. Deploying them too is a choice.
 */
class ImplicitDependenciesTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared@1.0.0`, `letters@1.0.0` pinning it, and `production`, which serves neither. */
    private fun setUp(slug: String): Pair<TenantKey, EnvironmentId> {
        val tenant = createTenant(slug).id
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateEnvironment(production, "Production").execute()
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant))), "Brand").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogVersion(tenant, shared, "1.0.0").execute()
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(letters, TenantId(tenant))), "House").execute()
            declareDependency(tenant, letters, on = shared)
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogVersion(tenant, letters, "1.0.0").execute()
        }
        return tenant to production
    }

    @Test
    fun `a deploy uses what it is pinned to without deploying it, and the environment says it uses it`() {
        val (tenant, production) = setUp("implicit-deploy")

        withMediator { DeployRelease(production, letters, "1.0.0", replace = true).execute() }

        assertThat(withMediator { ListDeployments(tenant).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .`as`("only what was deployed").containsExactly("letters@1.0.0")
        val implicit = withMediator { ListImplicitDeployments(tenant, production.key).query() }
        assertThat(implicit.map { Triple(it.catalogKey.value, it.version, it.neededBy.map { k -> k.value }) })
            .containsExactly(Triple("shared", "1.0.0", listOf("letters")))
    }

    @Test
    fun `asked to, it deploys them explicitly too`() {
        val (tenant, production) = setUp("explicit-deploy")

        withMediator { DeployRelease(production, letters, "1.0.0", includeDependencies = true).execute() }

        assertThat(withMediator { ListDeployments(tenant).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .containsExactlyInAnyOrder("letters@1.0.0", "shared@1.0.0")
        assertThat(withMediator { ListImplicitDeployments(tenant, production.key).query() }).`as`("served, so not implicit").isEmpty()
    }
}
