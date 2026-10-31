// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.FindStencilUsages
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.invariants.SkipCatalogInvariants
import app.epistola.suite.testing.testCatalog
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * #1024 / #983: stencil keys are unique only within a catalog. Two catalogs may each have a
 * `letterhead`, and a stencil node names which one through its `catalogKey` prop — or, without
 * one, means the stencil in its own template's catalog. Upgrades, usage queries and the stencil
 * lookup must all tell the two apart.
 */
class StencilCatalogScopeTest : IntegrationTestBase() {

    @Autowired private lateinit var stencilResolvers: StencilResolvers

    @Autowired private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    private val acme = CatalogKey.of("acme")
    private val brandB = CatalogKey.of("brand-b")
    private val letterhead = StencilKey.of("letterhead")

    private class Fixture(val tenantId: TenantId, val acmeLetterhead: StencilId, val brandBLetterhead: StencilId, val variantId: VariantId)

    private fun content(text: String) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "text1" to Node(id = "text1", type = "text", slots = emptyList(), props = mapOf("content" to richText(text))),
        ),
        slots = mapOf("slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("text1"))),
        themeRef = ThemeRef.Inherit,
    )

    private fun richText(text: String) = mapOf(
        "type" to "doc",
        "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))),
    )

    /** A template in `default` whose draft embeds the given stencil instances, each `nodeId to catalogKey`. */
    private fun templateEmbedding(vararg instances: Pair<String, String>): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot"))) +
            instances.associate { (nodeId, catalogKey) ->
                nodeId to Node(
                    id = nodeId,
                    type = "stencil",
                    slots = listOf("$nodeId-children"),
                    props = mapOf("stencilKey" to letterhead.value, "catalogKey" to catalogKey),
                )
            },
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = instances.map { it.first })) +
            instances.associate { (nodeId, _) ->
                "$nodeId-children" to Slot(id = "$nodeId-children", nodeId = nodeId, name = "children", children = emptyList())
            },
        themeRef = ThemeRef.Inherit,
    )

    /** `acme/letterhead` and `brand-b/letterhead`, and a template using [instances]. */
    private fun setUp(vararg instances: Pair<String, String>): Fixture = withMediator {
        val tenantId = TenantId(createTenant("Stencil scope").id)
        val stencils = listOf(acme, brandB).map { catalog ->
            CreateCatalog(tenantKey = tenantId.key, id = catalog, name = catalog.value).execute()
            val id = StencilId(letterhead, testCatalog(tenantId, catalog))
            CreateStencil(id = id, name = "${catalog.value} letterhead", content = content("${catalog.value} v1")).execute()
            id
        }
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter").execute()
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        UpdateVariantContent(variantId = variantId, templateModel = templateEmbedding(*instances)).execute()
        Fixture(tenantId, stencils[0], stencils[1], variantId)
    }

    @Test
    fun `each instance expands to the letterhead of the catalog it names`() {
        val f = setUp("acme-instance" to acme.value, "brand-b-instance" to brandB.value)

        withMediator {
            UpdateStencil(f.acmeLetterhead, content = content("acme v2")).execute()

            val expanded = stencilResolvers.expandWithWorkingCopies(testCatalog(f.tenantId.key, CatalogKey.DEFAULT), GetVariantContent(f.variantId).query()!!)
            assertThat(expanded.texts()).containsExactlyInAnyOrder("acme v2", "brand-b v1")
        }
    }

    @Test
    fun `usage queries count only instances of the stencil in their own catalog`() {
        val f = setUp("brand-b-instance" to brandB.value)

        withMediator {
            assertThat(FindStencilUsages(f.acmeLetterhead).query()).isEmpty()
            assertThat(GetStencilUsagePage(f.acmeLetterhead).query().total).isZero()

            assertThat(FindStencilUsages(f.brandBLetterhead).query()).containsExactly("Letter")
            assertThat(GetStencilUsagePage(f.brandBLetterhead).query().total).isEqualTo(1)
        }
    }

    @Test
    @SkipCatalogInvariants("leaves a draft naming a stencil nobody has, as only the 1.x upgrade does")
    fun `a reference whose catalog no longer has the stencil is reported under the stencil that has the key`() {
        // brand-b/letterhead is gone, so the instance naming it dangles. Usage reports it under the
        // remaining letterhead, as after a move, so an author can find and fix it.
        val f = setUp("brand-b-instance" to brandB.value)
        // Only the 1.x upgrade leaves a draft naming a stencil nobody has; no command does.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "DELETE FROM release_resources WHERE tenant_key = :t AND resource_type = 'stencil' AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = :c)",
            ).bind("t", f.tenantId.key).bind("c", brandB.value).execute()
        }

        withMediator {
            assertThat(FindStencilUsages(f.acmeLetterhead).query()).containsExactly("Letter")
            assertThat(GetStencilUsagePage(f.acmeLetterhead).query().total).isEqualTo(1)
        }
    }

    @Test
    fun `a stencil is not held in use by a same-named stencil from another catalog`() {
        val f = setUp("brand-b-instance" to brandB.value)

        assertThat(withMediator { DeleteStencil(f.acmeLetterhead).execute() }).isTrue()
    }

    @Test
    fun `a stencil is looked up in the catalog it is asked for`() {
        val f = setUp()

        withMediator {
            assertThat(GetStencil(f.acmeLetterhead).query()!!.name).isEqualTo("acme letterhead")
            assertThat(GetStencil(f.brandBLetterhead).query()!!.name).isEqualTo("brand-b letterhead")
        }
    }

    @Test
    fun `the usage page takes a page size, and a page past the end is the last one`() {
        val f = setUp("first-instance" to brandB.value)
        withMediator {
            repeat(2) {
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(f.tenantId))
                CreateDocumentTemplate(id = templateId, name = "Letter $it").execute()
                UpdateVariantContent(VariantId(VariantKey.INITIAL, templateId), templateEmbedding("instance-$it" to brandB.value)).execute()
            }

            val all = GetStencilUsagePage(f.brandBLetterhead).query()
            assertThat(all.total).isEqualTo(3)
            assertThat(all.items).hasSize(3)

            val second = GetStencilUsagePage(f.brandBLetterhead, page = 2, pageSize = 2).query()
            assertThat(second.totalPages).isEqualTo(2)
            assertThat(second.items.map { it.templateName }).containsExactly(all.items[2].templateName)
            assertThat(GetStencilUsagePage(f.brandBLetterhead, page = 9, pageSize = 2).query().page).isEqualTo(2)
        }
    }
}
