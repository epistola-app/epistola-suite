// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.environments.queries.GetGenerationReadiness
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What keeps a tenant's documents from being made: environments that serve nothing (and follow
 * nothing). Catalogs waiting for their 2.0 upgrade are counted in `CatalogUpgradeTest`.
 */
class GenerationReadinessTest : IntegrationTestBase() {

    @Test
    fun `an environment serving nothing is idle until a release is deployed or it follows a draft`() {
        val tenant = withMediator { createTenant("Readiness").id }
        val test = EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant))
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant))
        withMediator {
            CreateEnvironment(test, "Test").execute()
            CreateEnvironment(production, "Production", production = true).execute()
        }
        assertThat(withMediator { GetGenerationReadiness(tenant).query() }.idleEnvironments.map { it.second }).containsExactly("Production", "Test")

        withMediator {
            val version = mediator.releaseNext(app.epistola.suite.common.ids.CatalogId.default(TenantId(tenant)))
            DeployCatalogVersion(production, CatalogKey.DEFAULT, version).execute()
            DeployCatalogVersion(test, CatalogKey.DEFAULT, app.epistola.suite.catalog.versions.GetCatalogDraft(tenant, CatalogKey.DEFAULT).query()!!.versionId).execute()
        }
        assertThat(withMediator { GetGenerationReadiness(tenant).query() }.ready).isTrue()
    }
}
