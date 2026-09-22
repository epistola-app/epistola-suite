// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A batch is one operation: every member is planned against every other member's destination, and
 * it applies completely or not at all. These cover the shapes a single-member test cannot reach.
 */
class RelocationBatchTest : RelocationTestSupport() {

    @Test
    fun `two attributes named by one variant move together`() {
        val tenant = tenantWith("Two attributes one variant")
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand"), catalogId(tenant, letters)), "Brand", listOf("acme")).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), catalogId(tenant, letters)), "Tone", listOf("formal")).execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateVariant(variant, "Main", mapOf("letters.brand" to "acme", "letters.tone" to "formal")).execute()
        }
        val brand = address(CatalogResourceType.ATTRIBUTE, letters, "brand")
        val tone = address(CatalogResourceType.ATTRIBUTE, letters, "tone")

        val plan = move(tenant, brand.movedTo(shared), tone.movedTo(shared))

        // One variant, rewritten once for both.
        assertThat(plan.mutableRewriteCount).isEqualTo(1)
        assertThat(attributesOf(template, variant)).containsExactlyInAnyOrderEntriesOf(mapOf("shared.brand" to "acme", "shared.tone" to "formal"))
    }

    @Test
    fun `an attribute taking the address another member vacates keeps its own value`() {
        val tenant = tenantWith("Attribute handover")
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand"), catalogId(tenant, letters)), "Brand", listOf("old")).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand-next"), catalogId(tenant, letters)), "Next brand", listOf("new")).execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateVariant(variant, "Main", mapOf("letters.brand" to "old", "letters.brand-next" to "new")).execute()
        }

        // Retire the current brand attribute to shared, and promote its successor into the name.
        move(
            tenant,
            address(CatalogResourceType.ATTRIBUTE, letters, "brand").movedTo(shared),
            address(CatalogResourceType.ATTRIBUTE, letters, "brand-next").renamedTo("brand"),
        )

        assertThat(attributesOf(template, variant)).containsExactlyInAnyOrderEntriesOf(mapOf("shared.brand" to "old", "letters.brand" to "new"))
    }

    /**
     * A bare key resolves by slug across the tenant. A move does not change the slug, but a rename
     * does, and the variant would be left naming an attribute nothing answers to.
     */
    @Test
    fun `renaming an attribute re-points a variant that names it by bare slug`() {
        val tenant = tenantWith("Bare attribute rename")
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand"), catalogId(tenant, letters)), "Brand", listOf("acme")).execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateVariant(variant, "Main", mapOf("brand" to "acme")).execute()
        }

        move(tenant, address(CatalogResourceType.ATTRIBUTE, letters, "brand").renamedTo("label"))

        assertThat(attributesOf(template, variant)).containsExactlyEntriesOf(mapOf("letters.label" to "acme"))
        // And the variant is still valid against the registry: saving it again succeeds.
        withMediator { UpdateVariant(variant, "Main", attributesOf(template, variant)).execute() }
    }

    @Test
    fun `moving an attribute leaves a bare slug alone, since it still resolves`() {
        val tenant = tenantWith("Bare attribute move")
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand"), catalogId(tenant, letters)), "Brand", listOf("acme")).execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateVariant(variant, "Main", mapOf("brand" to "acme")).execute()
        }

        val plan = move(tenant, address(CatalogResourceType.ATTRIBUTE, letters, "brand").movedTo(shared))

        assertThat(plan.mutableRewriteCount).isZero()
        assertThat(attributesOf(template, variant)).containsExactlyEntriesOf(mapOf("brand" to "acme"))
    }

    @Test
    fun `a chain of three handovers applies in an order that never collides`() {
        val tenant = tenantWith("Three-member chain")
        val a = create(tenant, MovableResource.STENCIL, letters, "first")
        val b = create(tenant, MovableResource.STENCIL, letters, "second")
        val c = create(tenant, MovableResource.STENCIL, letters, "third")
        val identities = listOf(a, b, c).associateWith { identityAt(tenant, it)!! }

        // first leaves for shared; second takes its name; third takes second's.
        move(tenant, a.movedTo(shared), b.renamedTo("first"), c.renamedTo("second"))

        assertThat(identityAt(tenant, address(CatalogResourceType.STENCIL, shared, "first"))).isEqualTo(identities[a])
        assertThat(identityAt(tenant, a)).describedAs("letters/first is now the former second").isEqualTo(identities[b])
        assertThat(identityAt(tenant, b)).describedAs("letters/second is now the former third").isEqualTo(identities[c])
        assertThat(identityAt(tenant, c)).describedAs("letters/third is left empty").isNull()
    }

    @Test
    fun `a template moved with the stencil it inserts lands on the stencil's destination`() {
        val tenant = tenantWith("Template with its stencil")
        val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        withMediator {
            CreateStencil(header, "Header").execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateDraft(VariantId(VariantKey.INITIAL, template), templateEmbedding("header", letters.value)).execute()
        }

        move(
            tenant,
            address(CatalogResourceType.TEMPLATE, letters, "invoice").movedTo(shared),
            address(CatalogResourceType.STENCIL, letters, "header").movedTo(shared, "masthead"),
        )

        val movedVariant = VariantId(VariantKey.INITIAL, TemplateId(template.key, catalogId(tenant, shared)))
        val props = withMediator { GetDraft(movedVariant).query()!! }.templateModel.nodes.getValue("stencil-instance").props!!
        assertThat(props["catalogKey"]).isEqualTo(shared.value)
        assertThat(props["stencilId"]).isEqualTo("masthead")
    }

    @Test
    fun `a draft naming two moving resources is rewritten once, for both`() {
        val tenant = tenantWith("Two references one draft", listOf(letters, shared, archive))
        val template = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
        val variant = VariantId(VariantKey.INITIAL, template)
        withMediator {
            CreateStencil(StencilId(StencilKey.of("header"), catalogId(tenant, letters)), "Header").execute()
            CreateStencil(StencilId(StencilKey.of("footer"), catalogId(tenant, letters)), "Footer").execute()
            CreateDocumentTemplate(template, "Invoice").execute()
            UpdateDraft(variant, templateEmbedding(listOf(Triple("top", "header", letters.value), Triple("bottom", "footer", letters.value)))).execute()
        }

        val plan = move(
            tenant,
            address(CatalogResourceType.STENCIL, letters, "header").movedTo(shared),
            address(CatalogResourceType.STENCIL, letters, "footer").movedTo(archive, "closing"),
        )

        assertThat(plan.mutableRewriteCount).isEqualTo(1)
        val nodes = withMediator { GetDraft(variant).query()!! }.templateModel.nodes
        assertThat(nodes.getValue("top").props).containsEntry("catalogKey", shared.value).containsEntry("stencilId", "header")
        assertThat(nodes.getValue("bottom").props).containsEntry("catalogKey", archive.value).containsEntry("stencilId", "closing")
    }

    @Test
    fun `a batch of every type applies as one`() {
        val tenant = tenantWith("Every type at once")
        val batch = MovableResource.entries.map { movable ->
            create(tenant, movable, letters, "all-${movable.name.lowercase().replace('_', '-')}").movedTo(shared)
        }

        val identities = batch.associateWith { identityAt(tenant, it.source) }

        move(tenant, batch)

        for (relocation in batch) {
            assertThat(identityAt(tenant, relocation.target)).isEqualTo(identities[relocation])
            assertThat(identityAt(tenant, relocation.source)).isNull()
        }
    }

    /**
     * The cycle check is for the batch, not per member: moving a stencil alone into `shared` would
     * make `shared` depend on `letters` through the theme it uses, but moving the theme with it keeps
     * the dependency inside `shared`.
     */
    @Test
    fun `moving a dependency along resolves a cycle neither move could avoid alone`() {
        val tenant = tenantWith("Cycle resolved by batch")
        val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("base"), catalogId(tenant, shared)), "Base").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand").execute()
            CreateStencil(header, "Header").execute()
            UpdateStencilDraft(StencilVersionId(VersionKey.of(1), header), usesTheme("brand", letters.value)).execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            val invoice = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
            CreateDocumentTemplate(invoice, "Invoice").execute()
            UpdateDraft(VariantId(VariantKey.INITIAL, invoice), usesTheme("base", shared.value)).execute()
        }
        val stencil = address(CatalogResourceType.STENCIL, letters, "header")
        val theme = address(CatalogResourceType.THEME, letters, "brand")

        assertThat(preview(tenant, stencil.movedTo(shared)).blockers.map { it.code }).containsExactly("catalog-dependency-cycle")

        move(tenant, stencil.movedTo(shared), theme.movedTo(shared))

        assertThat(identityAt(tenant, stencil.copy(catalogKey = shared.value))).isNotNull()
    }

    private fun attributesOf(template: TemplateId, variant: VariantId): Map<String, String> = withMediator { ListVariants(template).query() }
        .single { it.id == variant.key }
        .attributes
}
