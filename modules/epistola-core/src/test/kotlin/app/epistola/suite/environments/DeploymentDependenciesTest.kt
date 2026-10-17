// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.MissingDependencyReason.CONTENT_NOT_RETAINED
import app.epistola.suite.environments.MissingDependencyReason.NOT_DEPLOYED
import app.epistola.suite.environments.MissingDependencyReason.NOT_INSTALLED
import app.epistola.suite.environments.MissingDependencyReason.UNAVAILABLE
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Deploying a release checks the releases of other catalogs it renders with, direct and transitive
 * (contract ADR 0003, `deployRelease`): refused while the environment does not serve one, unless
 * they are deployed along — all or nothing.
 *
 * The chain: `letters@1.0.0` pins `shared@1.0.0`, which pins `base@1.0.0`.
 */
class DeploymentDependenciesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val base = CatalogKey.of("base")
    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    @Test
    fun `a release whose dependencies the environment does not serve is refused, listing direct and transitive ones`() {
        val (tenant, production) = chain("deps-refused")

        val refusal = refusedDeploying(production, letters, includeDependencies = false)

        assertThat(refusal.missing.map { it.catalogKey to it.reason }).containsExactly(shared.value to NOT_DEPLOYED, base.value to NOT_DEPLOYED)
        assertThat(refusal.missing.first()).isEqualTo(
            MissingReleaseDependency(registry(tenant, shared), namespace(tenant, shared), shared.value, "1.0.0", NOT_DEPLOYED),
        )
        assertThat(refusal.deployableAlong).isTrue()
        assertThat(served(tenant)).`as`("nothing deployed").isEmpty()
        assertThat(withMediator { ListDeploymentHistory(production).query() }).`as`("nothing logged").isEmpty()
    }

    @Test
    fun `a dependency the environment already serves is not missing`() {
        val (tenant, production) = chain("deps-served")
        withMediator { DeployRelease(production, base, "1.0.0").execute() }

        assertThat(refusedDeploying(production, letters, includeDependencies = false).missing.map { it.catalogKey })
            .containsExactly(shared.value)

        withMediator { DeployRelease(production, shared, "1.0.0").execute() }
        withMediator { DeployRelease(production, letters, "1.0.0").execute() }
        assertThat(served(tenant)).containsExactlyInAnyOrder("base@1.0.0", "shared@1.0.0", "letters@1.0.0")
    }

    @Test
    fun `with includeDependencies the missing releases are deployed along, and each is logged`() {
        val (tenant, production) = chain("deps-along")

        val deployment = withMediator { DeployRelease(production, letters, "1.0.0", includeDependencies = true).execute() }

        assertThat(deployment.catalogKey to deployment.version).isEqualTo(letters to "1.0.0")
        assertThat(served(tenant)).containsExactlyInAnyOrder("base@1.0.0", "shared@1.0.0", "letters@1.0.0")
        assertThat(withMediator { ListDeploymentHistory(production).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .containsExactlyInAnyOrder("base@1.0.0", "shared@1.0.0", "letters@1.0.0")
    }

    @Test
    fun `a pinned release the tenant does not have is NOT_INSTALLED, and UNAVAILABLE when deploying along`() {
        val (tenant, production) = chain("deps-not-installed")
        // A pin on a release this tenant lacks arrives only with an imported release; no command cuts one.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_dependencies SET release_version = '9.9.9'
                WHERE tenant_key = :t AND dependency_key = 'shared'
                  AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                """,
            ).bind("t", tenant).execute()
        }

        val refused = refusedDeploying(production, letters, includeDependencies = false)
        assertThat(refused.missing.map { "${it.catalogKey}@${it.releaseVersion}" to it.reason }).containsExactly("shared@9.9.9" to NOT_INSTALLED)
        assertThat(refused.deployableAlong).`as`("deploying along cannot install it").isFalse()

        val along = refusedDeploying(production, letters, includeDependencies = true)
        assertThat(along.missing.map { it.catalogKey to it.reason }).containsExactly(shared.value to UNAVAILABLE)
        assertThat(served(tenant)).isEmpty()
    }

    @Test
    fun `a transitive dependency without content refuses deploying along, and nothing is deployed`() {
        val (tenant, production) = chain("deps-no-content")
        // A pinned release cannot lose its content through a command while its pinner keeps its own
        // (ReleaseInUse); a release imported without content is the state this stands for.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_releases SET content_retained = false
                WHERE tenant_key = :t AND version = '1.0.0'
                  AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'base')
                """,
            ).bind("t", tenant).execute()
        }

        val refused = refusedDeploying(production, letters, includeDependencies = false)
        assertThat(refused.missing.map { it.catalogKey to it.reason }).containsExactly(shared.value to NOT_DEPLOYED, base.value to CONTENT_NOT_RETAINED)
        assertThat(refused.deployableAlong).isFalse()

        val along = refusedDeploying(production, letters, includeDependencies = true)
        assertThat(along.missing.map { it.catalogKey to it.reason }).`as`("only what could not be deployed").containsExactly(base.value to CONTENT_NOT_RETAINED)
        assertThat(served(tenant)).`as`("all or nothing: shared, which could be, was not deployed either").isEmpty()
        assertThat(withMediator { ListDeploymentHistory(production).query() }).isEmpty()
    }

    @Test
    fun `a pin that recorded no release cannot be checked and is skipped`() {
        val (tenant, production) = chain("deps-unpinned")
        // A release imported from catalog wire v7 recorded no release for its dependencies.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_dependencies SET release_version = NULL, release_fingerprint = NULL
                WHERE tenant_key = :t AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                """,
            ).bind("t", tenant).execute()
        }

        withMediator { DeployRelease(production, letters, "1.0.0").execute() }

        assertThat(served(tenant)).containsExactly("letters@1.0.0")
    }

    private fun refusedDeploying(production: EnvironmentId, catalog: CatalogKey, includeDependencies: Boolean): ReleaseDependenciesMissingException {
        var refusal: ReleaseDependenciesMissingException? = null
        assertThatThrownBy { withMediator { DeployRelease(production, catalog, "1.0.0", includeDependencies).execute() } }
            .isInstanceOfSatisfying(ReleaseDependenciesMissingException::class.java) { refusal = it }
        return refusal!!
    }

    private fun served(tenant: TenantKey): List<String> = withMediator {
        ListDeployments(tenant).query().map { "${it.catalogKey.value}@${it.version}" }
    }

    private fun registry(tenant: TenantKey, catalog: CatalogKey) = withMediator { GetCatalog(tenant, catalog).query()!!.registry }

    private fun namespace(tenant: TenantKey, catalog: CatalogKey) = withMediator { GetCatalog(tenant, catalog).query()!!.namespace }

    /** `base`, `shared` and `letters`, each with a theme and released once, each pinning the one before; and `production`. */
    private fun chain(slug: String): Pair<TenantKey, EnvironmentId> {
        val tenant = createTenant(slug).id
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateEnvironment(production, "Production").execute()
            listOf(base, shared, letters).forEachIndexed { index, catalog ->
                CreateCatalog(tenantKey = tenant, id = catalog, name = catalog.value).execute()
                CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(catalog, TenantId(tenant))), "Brand").execute()
                if (index > 0) declareDependency(tenant, catalog, on = listOf(base, shared)[index - 1])
                MarkCatalogReady(tenant, catalog).execute()
                ReleaseCatalogVersion(tenant, catalog, "1.0.0").execute()
            }
        }
        return tenant to production
    }
}
