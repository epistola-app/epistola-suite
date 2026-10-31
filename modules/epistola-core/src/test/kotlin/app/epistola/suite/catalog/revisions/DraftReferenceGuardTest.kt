// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.invariants.SkipCatalogInvariants
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A save never makes a draft name what nothing it reads has, and never stops an author editing a
 * draft that already does (one upgraded from 1.x).
 */
class DraftReferenceGuardTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `saving content that inserts a stencil nobody has is refused`() {
        val (_, variant) = letterWithTemplate("guard-new")

        assertThatThrownBy { withMediator { UpdateVariantContent(variant, inserting("no-such-stencil")).execute() } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("stencil no-such-stencil")
            .extracting { (it as ValidationException).code }.isEqualTo(ValidationCode.CATALOG_REFERENCE_UNRESOLVED)
    }

    @Test
    fun `content inserting a stencil its catalog has is saved`() {
        val (letters, variant) = letterWithTemplate("guard-order")
        withMediator {
            CreateStencil(StencilId(StencilKey.of("header"), letters), "Header").execute()
            UpdateVariantContent(variant, inserting("header")).execute()
        }

        assertThat(withMediator { GetVariantContent(variant).query() }!!.nodes).containsKey("stencil-instance")
    }

    @Test
    @SkipCatalogInvariants("leaves a draft naming what nothing has, as only the 1.x upgrade does")
    fun `a draft that already names what nothing has can still be saved`() {
        val (letters, variant) = letterWithTemplate("guard-kept")
        withMediator {
            CreateStencil(StencilId(StencilKey.of("header"), letters), "Header").execute()
            UpdateVariantContent(variant, inserting("header")).execute()
        }
        // Only the 1.x upgrade leaves a draft naming what nothing has; no command does any more.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("DELETE FROM release_resources WHERE tenant_key = :t AND resource_type = 'stencil' AND resource_key = 'header'")
                .bind("t", letters.tenantKey).execute()
        }

        withMediator { UpdateVariantContent(variant, inserting("header", nodeId = "moved-instance")).execute() }

        assertThat(withMediator { GetVariantContent(variant).query() }!!.nodes).containsKey("moved-instance")
    }

    private fun letterWithTemplate(name: String): Pair<CatalogId, VariantId> {
        val tenant = createTenant(name).id
        val letters = catalogIn(tenant, "letters")
        val templateId = TemplateId(TemplateKey.of("invoice"), letters)
        withMediator { CreateDocumentTemplate(templateId, "Invoice").execute() }
        return letters to VariantId(VariantKey.INITIAL, templateId)
    }

    private fun catalogIn(tenant: TenantKey, slug: String): CatalogId {
        val key = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant, id = key, name = slug).execute() }
        return testCatalog(TenantId(tenant), key)
    }

    private fun inserting(stencilKey: String, nodeId: String = "stencil-instance") = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            nodeId to Node(id = nodeId, type = "stencil", props = mapOf("stencilKey" to stencilKey)),
        ),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf(nodeId))),
        themeRef = ThemeRef.Inherit,
    )
}
