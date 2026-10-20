// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.queries.GetStencil
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

            val stencil = GetStencil(id = id).query()

            assertThat(stencil).isNotNull
            assertThat(stencil!!.content.root).isEqualTo("root")
            assertThat(stencil.content.nodes).containsKey("root")
            assertThat(stencil.content.slots).containsKey("slot-root")
            assertThat(stencil.readsTemplateData).isFalse()
        }
    }
}
