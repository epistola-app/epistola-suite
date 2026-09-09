// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ImportAttribute
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class GetTenantResourceGraphIntegrationTest : IntegrationTestBase() {
    @Test
    fun `nodes carry the stable identity that survives a relocation`() {
        val tenant = createTenant("Graph identity")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val stencilId = StencilId(StencilKey.of("header"), CatalogId(letters, tenantId))
        val address = ResourceAddress(CatalogResourceType.STENCIL, letters.value, stencilId.key.value)

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateStencil(stencilId, "Header").execute()
        }

        val before = withMediator { GetTenantResourceGraph(tenant.id).query() }
            .nodes.single { it.address == address }

        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(shared))).query() }
        withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(shared)), preview.planFingerprint).execute() }

        // The address changed; the identity did not. That is what lets a caller follow a resource
        // across a move rather than guessing where it landed.
        val after = withMediator { GetTenantResourceGraph(tenant.id).query() }
            .nodes.single { it.resourceId == before.resourceId }
        assertThat(after.address.catalogKey).isEqualTo(shared.value)
        assertThat(after.address).isNotEqualTo(before.address)
    }

    @Test
    fun `extracts command-created references with resolution and lifecycle evidence`() {
        val tenant = createTenant("Resource Graph")
        val tenantId = TenantId(tenant.id)
        val lettersCatalog = CatalogKey.of("letters")
        val brandCatalog = CatalogKey.of("brand")
        val assetKey = AssetKey(UUID.fromString("01900000-0000-7000-8000-000000000001"))
        val missingAssetKey = AssetKey(UUID.fromString("01900000-0000-7000-8000-000000000099"))

        withMediator {
            CreateCatalog(tenant.id, lettersCatalog, "Letters").execute()
            CreateCatalog(tenant.id, brandCatalog, "Brand").execute()
            CreateTheme(
                id = ThemeId(ThemeKey.of("corporate"), CatalogId(brandCatalog, tenantId)),
                name = "Corporate",
                documentStyles = mapOf("fontFamily" to mapOf("slug" to "inter", "catalogKey" to "system")),
            ).execute()
            UploadAsset(
                tenantId = tenant.id,
                name = "Logo",
                mediaType = AssetMediaType.PNG,
                content = byteArrayOf(1),
                width = 1,
                height = 1,
                catalogKey = brandCatalog,
                id = assetKey,
            ).execute()
            ImportAttribute(
                tenantId = tenantId,
                catalogKey = brandCatalog,
                slug = "audience",
                displayName = "Audience",
                allowedValues = listOf("customer"),
            ).execute()

            val templateId = TemplateId(TemplateKey.of("welcome"), CatalogId(lettersCatalog, tenantId))
            CreateDocumentTemplate(templateId, "Welcome").execute().withRequiredDataExample()
            UpdateDocumentTemplate(templateId, themeId = ThemeKey.of("corporate"), themeCatalogKey = brandCatalog).execute()
            val variantId = VariantId(VariantKey.INITIAL, templateId)
            UpdateVariant(variantId, "Default", mapOf("brand.audience" to "customer")).execute()
            UpdateDraft(variantId, templateWithAsset(assetKey, brandCatalog)).execute()
            val firstDraft = GetDraft(variantId).query()!!
            PublishVersion(VersionId(firstDraft.id, variantId)).execute()
            ArchiveVersion(VersionId(firstDraft.id, variantId)).execute()
            UpdateDraft(variantId, templateWithAsset(missingAssetKey, brandCatalog)).execute()
        }

        val live = withMediator { GetTenantResourceGraph(tenant.id).query() }
        val withHistory = withMediator { GetTenantResourceGraph(tenant.id, includeHistory = true).query() }

        assertThat(live.edges).anySatisfy { edge ->
            assertThat(edge.kind).isEqualTo("template-default-theme")
            assertThat(edge.source).isEqualTo(ResourceAddress(CatalogResourceType.TEMPLATE, "letters", "welcome"))
            assertThat(edge.target).isEqualTo(ResourceAddress(CatalogResourceType.THEME, "brand", "corporate"))
            assertThat(edge.resolution).isEqualTo(ReferenceResolution.RESOLVED)
        }
        assertThat(live.edges).anySatisfy { edge ->
            assertThat(edge.kind).isEqualTo("variant-attribute")
            assertThat(edge.target).isEqualTo(ResourceAddress(CatalogResourceType.ATTRIBUTE, "brand", "audience"))
            assertThat(edge.qualification).isEqualTo(ReferenceQualification.EXPLICIT)
        }
        assertThat(live.edges).anySatisfy { edge ->
            assertThat(edge.kind).isEqualTo("image-asset")
            assertThat(edge.targetSelector.key).isEqualTo(missingAssetKey.toString())
            assertThat(edge.resolution).isEqualTo(ReferenceResolution.MISSING)
            assertThat(edge.evidence.single().lifecycle).isEqualTo(ReferenceLifecycle.LIVE)
        }
        assertThat(live.edges).noneMatch { edge -> edge.targetSelector.key == assetKey.toString() }
        assertThat(withHistory.edges).anySatisfy { edge ->
            assertThat(edge.target?.key).isEqualTo(assetKey.toString())
            assertThat(edge.resolution).isEqualTo(ReferenceResolution.RESOLVED)
            assertThat(edge.evidence.single().lifecycle).isEqualTo(ReferenceLifecycle.HISTORICAL)
        }
    }

    private fun templateWithAsset(assetKey: AssetKey, catalogKey: CatalogKey): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            "image" to Node(
                id = "image",
                type = "image",
                props = mapOf("assetId" to assetKey.toString(), "catalogKey" to catalogKey.value),
            ),
        ),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf("image"))),
        themeRef = ThemeRef.Inherit,
    )
}
