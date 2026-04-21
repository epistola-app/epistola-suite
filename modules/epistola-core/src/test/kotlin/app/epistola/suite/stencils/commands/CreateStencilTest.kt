package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CreateStencilTest : IntegrationTestBase() {

    @Test
    fun `create stencil without content produces a loadable draft version`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)
        val id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))

        withMediator {
            CreateStencil(
                id = id,
                name = "Empty Stencil",
            ).execute()

            val versionId = StencilVersionId(VersionKey.of(1), id)
            val version = GetStencilVersion(versionId = versionId).query()

            assertThat(version).isNotNull
            assertThat(version!!.content).isNotNull
            assertThat(version.content.root).isNotNull
            assertThat(version.content.root).isEqualTo("root")
            assertThat(version.content.nodes).containsKey("root")
            assertThat(version.content.slots).containsKey("slot-root")
        }
    }
}
