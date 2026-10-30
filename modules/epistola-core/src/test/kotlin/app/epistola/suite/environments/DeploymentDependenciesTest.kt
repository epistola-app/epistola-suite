// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.dependencies.MissingDependencyReason.UNAVAILABLE
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.commands.UndeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.environments.queries.ListImplicitDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.invariants.SkipCatalogInvariants
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Deploying a release uses the releases of other catalogs it renders with, direct and transitive,
 * implicitly (contract ADR 0003, `deployRelease`): they must be there to render from, and are
 * deployed too only when asked — all or nothing.
 *
 * The chain: `letters@1.0.0` pins `shared@1.0.0`, which pins `base@1.0.0`.
 */
@SkipCatalogInvariants("simulates releases imported with a pin on a release the tenant lacks, or with none, by rewriting their pins")
class DeploymentDependenciesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val base = CatalogKey.of("base")
    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    @Test
    fun `a release whose dependencies the environment does not serve is deployed, using them implicitly, direct and transitive`() {
        val (tenant, production) = chain("deps-implicit")

        withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0").execute() }

        assertThat(served(tenant)).`as`("only what was deployed").containsExactly("letters@1.0.0")
        assertThat(implicit(tenant, production)).containsExactlyInAnyOrder("shared@1.0.0", "base@1.0.0")
        assertThat(withMediator { ListDeploymentHistory(production).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .`as`("only what was deployed is logged").containsExactly("letters@1.0.0")
    }

    @Test
    fun `a dependency the environment serves itself is not used implicitly`() {
        val (tenant, production) = chain("deps-served")
        withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, base), "1.0.0").execute() }

        withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0").execute() }

        assertThat(served(tenant)).containsExactlyInAnyOrder("base@1.0.0", "letters@1.0.0")
        assertThat(implicit(tenant, production)).containsExactly("shared@1.0.0")
    }

    @Test
    fun `with includeDependencies the missing releases are deployed along, and each is logged`() {
        val (tenant, production) = chain("deps-along")

        val deployment = withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0", includeDependencies = true).execute() }

        assertThat(deployment.catalogKey to deployment.version).isEqualTo(letters to "1.0.0")
        assertThat(served(tenant)).containsExactlyInAnyOrder("base@1.0.0", "shared@1.0.0", "letters@1.0.0")
        assertThat(withMediator { ListDeploymentHistory(production).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .containsExactlyInAnyOrder("base@1.0.0", "shared@1.0.0", "letters@1.0.0")
    }

    @Test
    fun `undeploying a release another served release renders with is not refused, and it is used implicitly from then on`() {
        val (tenant, production) = chain("deps-undeploy-used")
        withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0", includeDependencies = true).execute() }

        val undeployed = withMediator { UndeployCatalogRelease(production, testCatalog(production.tenantKey, shared), "1.0.0").execute() }

        assertThat(undeployed).isTrue()
        assertThat(served(tenant)).containsExactlyInAnyOrder("base@1.0.0", "letters@1.0.0")
        assertThat(implicit(tenant, production)).`as`("letters still renders with it").containsExactly("shared@1.0.0")
    }

    @Test
    fun `a release used only implicitly is not deployed, so there is nothing to undeploy`() {
        val (tenant, production) = chain("deps-undeploy-implicit")
        withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0").execute() }

        val undeployed = withMediator { UndeployCatalogRelease(production, testCatalog(production.tenantKey, shared), "1.0.0").execute() }

        assertThat(undeployed).`as`("REST answers 404").isFalse()
        assertThat(implicit(tenant, production)).containsExactlyInAnyOrder("shared@1.0.0", "base@1.0.0")
    }

    @Test
    fun `a pinned release the tenant does not have refuses the deployment, as nothing could render from it`() {
        val (tenant, production) = chain("deps-not-installed")
        // A pin on a release this tenant lacks arrives only with an imported release; no command cuts one.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_version_dependencies SET release_version = '9.9.9', target_version_id = NULL
                WHERE tenant_key = :t AND dependency_key = 'shared'
                  AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                  AND version_id IN (SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND state = 'released')
                """,
            ).bind("t", tenant).execute()
        }

        val refused = refusedDeploying(production, letters, includeDependencies = false)
        assertThat(refused.missing.map { "${it.catalogKey}@${it.releaseVersion}" to it.reason }).containsExactly("shared@9.9.9" to UNAVAILABLE)
        assertThat(refused.deployableAlong).`as`("deploying along cannot install it").isFalse()

        val along = refusedDeploying(production, letters, includeDependencies = true)
        assertThat(along.missing.map { it.catalogKey to it.reason }).containsExactly(shared.value to UNAVAILABLE)
        assertThat(served(tenant)).isEmpty()
    }

    @Test
    fun `a pin that recorded no release cannot be checked and is skipped`() {
        val (tenant, production) = chain("deps-unpinned")
        // A release imported from catalog wire v7 recorded no release for its dependencies.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_version_dependencies SET release_version = NULL, release_fingerprint = NULL, target_version_id = NULL
                WHERE tenant_key = :t AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                  AND version_id IN (SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND state = 'released')
                """,
            ).bind("t", tenant).execute()
        }

        withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, letters), "1.0.0").execute() }

        assertThat(served(tenant)).containsExactly("letters@1.0.0")
    }

    private fun refusedDeploying(production: EnvironmentId, catalog: CatalogKey, includeDependencies: Boolean): ReleaseDependenciesMissingException {
        var refusal: ReleaseDependenciesMissingException? = null
        assertThatThrownBy { withMediator { DeployCatalogRelease(production, testCatalog(production.tenantKey, catalog), "1.0.0", includeDependencies).execute() } }
            .isInstanceOfSatisfying(ReleaseDependenciesMissingException::class.java) { refusal = it }
        return refusal!!
    }

    private fun implicit(tenant: TenantKey, environment: EnvironmentId): List<String> = withMediator {
        ListImplicitDeployments(tenant, environment.key).query().map { "${it.catalogKey.value}@${it.version}" }
    }

    private fun served(tenant: TenantKey): List<String> = withMediator {
        ListDeployments(tenant).query().map { "${it.catalogKey.value}@${it.version}" }
    }

    private fun registry(tenant: TenantKey, catalog: CatalogKey) = withMediator { GetCatalog(testCatalog(tenant, catalog)).query()!!.registry }

    private fun namespace(tenant: TenantKey, catalog: CatalogKey) = withMediator { GetCatalog(testCatalog(tenant, catalog)).query()!!.namespace }

    /** `base`, `shared` and `letters`, each with a theme and released once, each pinning the one before; and `production`. */
    private fun chain(slug: String): Pair<TenantKey, EnvironmentId> {
        val tenant = createTenant(slug).id
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateEnvironment(production, "Production").execute()
            listOf(base, shared, letters).forEachIndexed { index, catalog ->
                CreateCatalog(tenantKey = tenant, id = catalog, name = catalog.value).execute()
                CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), catalog)), "Brand").execute()
                if (index > 0) declareDependency(tenant, catalog, on = listOf(base, shared)[index - 1])
                MarkCatalogReady(testCatalog(tenant, catalog)).execute()
                ReleaseCatalogDraft(testCatalog(tenant, catalog), "1.0.0").execute()
            }
        }
        return tenant to production
    }
}
