// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class CatalogResourceIdentityIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `command-created resources receive stable internal identities`() {
        val tenant = createTenant("Resource identities")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("letters")
        val catalogId = CatalogId(catalogKey, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), catalogId)

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId), "Brand").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId), "Invoice").execute()
        }

        val resources = jdbi.withHandle<List<IdentityRow>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT resource_id, resource_type, catalog_key::text, resource_key
                FROM catalog_resources
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                ORDER BY resource_type
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("catalogKey", catalogKey)
                .map { rs, _ ->
                    IdentityRow(
                        rs.getObject("resource_id", UUID::class.java),
                        rs.getString("resource_type"),
                        rs.getString("catalog_key"),
                        rs.getString("resource_key"),
                    )
                }.list()
        }

        assertThat(resources.map { it.type to it.key }).containsExactly(
            "stencil" to "header",
            "template" to "invoice",
            "theme" to "brand",
        )
        assertThat(resources.map { it.resourceId }).doesNotHaveDuplicates()
        assertThat(resources).allMatch { it.catalogKey == "letters" }

        withMediator { DeleteStencil(stencilId).execute() }

        val deletedIdentityExists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT EXISTS(
                    SELECT 1 FROM catalog_resources
                    WHERE tenant_key = :tenantKey
                      AND resource_type = 'stencil'
                      AND catalog_key = :catalogKey
                      AND resource_key = 'header'
                )
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("catalogKey", catalogKey)
                .mapTo(Boolean::class.java)
                .one()
        }
        assertThat(deletedIdentityExists).isFalse()
    }

    private data class IdentityRow(
        val resourceId: UUID,
        val type: String,
        val catalogKey: String,
        val key: String,
    )
}
