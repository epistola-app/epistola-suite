// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.ReleaseCatalogResourceAlias
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * ADR 0014 requires a move to record what moved where. Relocation changes the address every
 * surface names a resource by, so an audit trail that says only "MoveCatalogResources" cannot
 * answer the question it exists for: where did this resource go, and when.
 */
class RelocationAuditIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a move records each resource's old and new address`() {
        val tenant = createTenant("Relocation audit")
        val header = ResourceAddress(CatalogResourceType.STENCIL, "letters", "header")
        val relocation = header.movedTo(CatalogKey.of("shared"), "masthead")
        withMediator {
            CreateCatalog(tenant.id, CatalogKey.of("letters"), "Letters").execute()
            CreateCatalog(tenant.id, CatalogKey.of("shared"), "Shared").execute()
            CreateStencil(StencilId(StencilKey.of("header"), CatalogId(CatalogKey.of("letters"), TenantId(tenant.id))), "Header").execute()
            val plan = PreviewCatalogResourceMove(tenant.id, listOf(relocation)).query()
            MoveCatalogResources(tenant.id, listOf(relocation), plan.planFingerprint).execute()
        }

        assertThat(latestDetails(tenant.id.value, "MoveCatalogResources"))
            .contains("stencil:letters/header", "stencil:shared/masthead")
    }

    @Test
    fun `releasing an alias records which address was given up`() {
        val tenant = createTenant("Alias release audit")
        val old = ResourceAddress(CatalogResourceType.STENCIL, "letters", "header")
        withMediator { ReleaseCatalogResourceAlias(tenant.id, old).execute() }

        assertThat(latestDetails(tenant.id.value, "ReleaseCatalogResourceAlias")).contains("stencil:letters/header")
    }

    private fun latestDetails(tenantKey: String, action: String): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT details::text FROM audit_log
            WHERE tenant_key = :tenantKey AND action = :action
            ORDER BY occurred_at DESC, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("action", action)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }
}
