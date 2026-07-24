// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.queries.GetEnvironment
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnvironmentQueriesTest : IntegrationTestBase() {

    @Test
    fun `GetEnvironment returns the environment`(): Unit = withMediator {
        val tenant = createTenant("Env Get")
        val tenantId = TenantId(tenant.id)
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId)
        val created = CreateEnvironment(id = envId, name = "Staging").execute()

        val found = GetEnvironment(id = envId).query()

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(created.id)
        assertThat(found.tenantKey).isEqualTo(tenant.id)
        assertThat(found.name).isEqualTo("Staging")
        assertThat(found.createdAt).isNotNull()
    }

    @Test
    fun `GetEnvironment returns null for an unknown environment`(): Unit = withMediator {
        val tenant = createTenant("Env Get Missing")
        val envId = EnvironmentId(TestIdHelpers.nextEnvironmentId(), TenantId(tenant.id))

        assertThat(GetEnvironment(id = envId).query()).isNull()
    }

    @Test
    fun `GetEnvironment does not leak environments across tenants`(): Unit = withMediator {
        val owner = createTenant("Env Get Owner")
        val other = createTenant("Env Get Other")
        val envKey = TestIdHelpers.nextEnvironmentId()
        CreateEnvironment(id = EnvironmentId(envKey, TenantId(owner.id)), name = "Staging").execute()

        val crossTenant = GetEnvironment(id = EnvironmentId(envKey, TenantId(other.id))).query()

        assertThat(crossTenant).isNull()
    }

    @Test
    fun `ListEnvironments returns the tenant's environments ordered by name`(): Unit = withMediator {
        val tenant = createTenant("Env List")
        val tenantId = TenantId(tenant.id)
        for (name in listOf("Gamma", "Alpha", "Beta")) {
            CreateEnvironment(id = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId), name = name).execute()
        }

        val environments = ListEnvironments(tenantId = tenantId).query()

        assertThat(environments).extracting<String> { it.name }.containsExactly("Alpha", "Beta", "Gamma")
    }

    @Test
    fun `ListEnvironments filters by search term`(): Unit = withMediator {
        val tenant = createTenant("Env List Search")
        val tenantId = TenantId(tenant.id)
        CreateEnvironment(id = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId), name = "Production").execute()
        CreateEnvironment(id = EnvironmentId(TestIdHelpers.nextEnvironmentId(), tenantId), name = "Staging").execute()

        val matches = ListEnvironments(tenantId = tenantId, searchTerm = "prod").query()

        assertThat(matches).extracting<String> { it.name }.containsExactly("Production")
    }

    @Test
    fun `ListEnvironments is tenant-scoped`(): Unit = withMediator {
        val tenant1 = createTenant("Env List Tenant 1")
        val tenant2 = createTenant("Env List Tenant 2")
        CreateEnvironment(id = EnvironmentId(TestIdHelpers.nextEnvironmentId(), TenantId(tenant1.id)), name = "Only Here").execute()

        assertThat(ListEnvironments(tenantId = TenantId(tenant1.id)).query()).hasSize(1)
        assertThat(ListEnvironments(tenantId = TenantId(tenant2.id)).query()).isEmpty()
    }
}
