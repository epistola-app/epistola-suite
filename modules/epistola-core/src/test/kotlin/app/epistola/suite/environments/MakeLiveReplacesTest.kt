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
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListImplicitDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Deploying a version with `replace` (the UI's "deploy", one deployed version per catalog per
 * environment): the catalog's other versions stop being served, in the same transaction; one another
 * deployed release is pinned to is used implicitly from then on.
 */
class MakeLiveReplacesTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared@1.0.0`, `letters@1.0.0` pinning it, and `production`. */
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

    private fun releaseShared(tenant: TenantKey, version: String, theme: String) = withMediator {
        CreateTheme(ThemeId(ThemeKey.of(theme), CatalogId(shared, TenantId(tenant))), theme).execute()
        MarkCatalogReady(tenant, shared).execute()
        ReleaseCatalogVersion(tenant, shared, version).execute()
    }

    private fun served(tenant: TenantKey): List<String> = withMediator { ListDeployments(tenant).query().map { "${it.catalogKey.value}@${it.version}" } }

    @Test
    fun `making a version live replaces the one that was live, and the history says so`() {
        val (tenant, production) = setUp("live-replace")
        withMediator { DeployCatalogVersion(production, shared, "1.0.0").execute() }
        releaseShared(tenant, "1.1.0", "second")

        withMediator { DeployCatalogVersion(production, shared, "1.1.0", replace = true).execute() }

        assertThat(served(tenant)).containsExactly("shared@1.1.0")
        val latest = withMediator { ListDeploymentHistory(production).query() }.first()
        assertThat(Triple(latest.action, latest.version, latest.previousVersion)).`as`("one entry: made v1.1.0 live, replacing v1.0.0")
            .isEqualTo(Triple(DeploymentAction.DEPLOYED, "1.1.0", "1.0.0"))
    }

    @Test
    fun `a version another deployed release is pinned to goes when a newer one replaces it, and is used implicitly`() {
        val (tenant, production) = setUp("live-replace-needed")
        withMediator { DeployCatalogVersion(production, letters, "1.0.0", includeDependencies = true).execute() }
        releaseShared(tenant, "1.1.0", "second")

        withMediator { DeployCatalogVersion(production, shared, "1.1.0", replace = true).execute() }

        assertThat(served(tenant)).`as`("one deployed version per catalog").containsExactlyInAnyOrder("letters@1.0.0", "shared@1.1.0")
        assertThat(withMediator { ListImplicitDeployments(tenant, production.key).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .`as`("letters@1.0.0 still renders with shared@1.0.0").containsExactly("shared@1.0.0")
    }

    @Test
    fun `replacing a version also lets go of what only the replaced one needed`() {
        val (tenant, production) = setUp("live-replace-own")
        withMediator { DeployCatalogVersion(production, letters, "1.0.0", includeDependencies = true).execute() }
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("second"), CatalogId(letters, TenantId(tenant))), "Second").execute()
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogVersion(tenant, letters, "1.1.0").execute()
        }

        withMediator { DeployCatalogVersion(production, letters, "1.1.0", includeDependencies = true, replace = true).execute() }

        assertThat(served(tenant)).`as`("letters' own earlier version goes; shared stays, the new one pins it too")
            .containsExactlyInAnyOrder("letters@1.1.0", "shared@1.0.0")
    }

    @Test
    fun `without replace a version is served beside the others, as the contract's deployRelease does`() {
        val (tenant, production) = setUp("live-beside")
        withMediator { DeployCatalogVersion(production, shared, "1.0.0").execute() }
        releaseShared(tenant, "1.1.0", "second")

        withMediator { DeployCatalogVersion(production, shared, "1.1.0").execute() }

        assertThat(served(tenant)).containsExactlyInAnyOrder("shared@1.0.0", "shared@1.1.0")
    }
}
