// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.node.JsonNodeFactory

/**
 * Rendering reads a catalog release, never the working copy.
 *
 * Each case changes the working copy after releasing and checks the release still answers with what
 * it held, because "reads the release" is only distinguishable from "reads the current state" once
 * the two differ.
 */
class ReleaseRenderSourceTest : IntegrationTestBase() {

    @Autowired
    private lateinit var source: ReleaseRenderSource

    @Test
    fun `a release renders its own theme, model and contract after the working copy moves on`() {
        val catalog = authoredCatalog("rr-own")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand", documentStyles = mapOf("color" to "#ff0000")).execute()
            invoice(catalog, theme = ThemeKey.of("brand"), nodeId = "original", withSchema = true)
            release(catalog, "1.0.0")

            UpdateTheme(ThemeId(ThemeKey.of("brand"), catalog), documentStyles = mapOf("color" to "#0000ff")).execute()
            publish(variant(catalog), model("changed"))
        }

        val inputs = source.resolve(tenantOf(catalog), ReleaseRef(catalog.key, "1.0.0"), "invoice", VariantKey.INITIAL.value)

        assertThat(inputs.resolvedTheme.documentStyles).containsEntry("color", "#ff0000")
        assertThat(inputs.templateModel.nodes).containsKey("original").doesNotContainKey("changed")
        assertThat(inputs.dataModel?.get("type")?.asString()).`as`("the contract travels with the release").isEqualTo("object")
        assertThat(inputs.templateName).isEqualTo("Invoice")
    }

    @Test
    fun `a theme from another catalog renders as the release of that catalog it pinned`() {
        val tenant = createTenant("rr-pin")
        val shared = catalogIn(tenant.id, "shared")
        val letters = catalogIn(tenant.id, "letters")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand", documentStyles = mapOf("color" to "#ff0000")).execute()
            release(shared, "1.0.0")
            invoice(letters, theme = ThemeKey.of("brand"), themeCatalog = shared.key, nodeId = "body")
            release(letters, "1.0.0")

            UpdateTheme(ThemeId(ThemeKey.of("brand"), shared), documentStyles = mapOf("color" to "#0000ff")).execute()
            release(shared, "1.1.0")
            release(letters, "1.1.0")
        }

        val pinnedFirst = source.resolve(tenantOf(letters), ReleaseRef(letters.key, "1.0.0"), "invoice", VariantKey.INITIAL.value)
        val pinnedSecond = source.resolve(tenantOf(letters), ReleaseRef(letters.key, "1.1.0"), "invoice", VariantKey.INITIAL.value)

        assertThat(pinnedFirst.resolvedTheme.documentStyles)
            .`as`("letters@1.0.0 pinned shared@1.0.0, so a later release of shared does not reach it")
            .containsEntry("color", "#ff0000")
        assertThat(pinnedSecond.resolvedTheme.documentStyles).containsEntry("color", "#0000ff")
    }

    @Test
    fun `releasing is refused while a catalog it uses has no release`() {
        val tenant = createTenant("rr-unreleased")
        val shared = catalogIn(tenant.id, "shared")
        val letters = catalogIn(tenant.id, "letters")

        assertThatThrownBy {
            withMediator {
                CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
                invoice(letters, theme = ThemeKey.of("brand"), themeCatalog = shared.key, nodeId = "body")
                release(letters, "1.0.0")
            }
        }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("'shared'")
    }

    @Test
    fun `an image renders from the bytes the release kept, after the asset is deleted`() {
        val catalog = authoredCatalog("rr-image")
        val bytes = ByteArray(96) { ((it * 7) % 256).toByte() }
        val image = withMediator {
            val asset = UploadAsset(
                tenantId = catalog.tenantKey,
                name = "logo.png",
                mediaType = AssetMediaType.PNG,
                content = bytes,
                width = 1,
                height = 1,
                catalogKey = catalog.key,
            ).execute().id
            invoice(catalog, theme = null, nodeId = "body")
            release(catalog, "1.0.0")
            DeleteAsset(catalog.tenantKey, asset).execute()
            asset
        }

        val inputs = source.resolve(tenantOf(catalog), ReleaseRef(catalog.key, "1.0.0"), "invoice", VariantKey.INITIAL.value)
        val resolved = inputs.assetResolver.resolve(image.value, null)

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.content).isEqualTo(bytes)
        assertThat(resolved.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `a template or variant the release does not contain is refused, not looked up elsewhere`() {
        val catalog = authoredCatalog("rr-missing")
        withMediator {
            invoice(catalog, theme = null, nodeId = "body")
            release(catalog, "1.0.0")
        }
        val tenant = tenantOf(catalog)

        assertThatThrownBy { source.resolve(tenant, ReleaseRef(catalog.key, "1.0.0"), "letter", VariantKey.INITIAL.value) }
            .isInstanceOf(ReleaseRenderException::class.java)
            .hasMessageContaining("does not contain template 'letter'")
        assertThatThrownBy { source.resolve(tenant, ReleaseRef(catalog.key, "1.0.0"), "invoice", "nl") }
            .isInstanceOf(ReleaseRenderException::class.java)
            .hasMessageContaining("no variant 'nl'")
        assertThatThrownBy { source.resolve(tenant, ReleaseRef(catalog.key, "2.0.0"), "invoice", VariantKey.INITIAL.value) }
            .isInstanceOf(ReleaseRenderException::class.java)
            .hasMessageContaining("does not exist")
    }

    private fun invoice(catalog: CatalogId, theme: ThemeKey?, themeCatalog: CatalogKey? = null, nodeId: String, withSchema: Boolean = false) {
        val templateId = TemplateId(TemplateKey.of("invoice"), catalog)
        CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
        if (withSchema) {
            // Before publishing, as an author would: publishing the template publishes its contract draft.
            UpdateContractVersion(templateId = templateId, dataModel = JsonNodeFactory.instance.objectNode().put("type", "object")).execute()
        }
        if (theme != null) {
            UpdateDocumentTemplate(templateId, themeId = theme, themeCatalogKey = themeCatalog ?: catalog.key).execute()
        }
        publish(variant(catalog), model(nodeId))
    }

    private fun publish(variant: VariantId, model: TemplateDocument) {
        UpdateDraft(variant, model).execute()
        PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
    }

    private fun release(catalog: CatalogId, version: String) {
        ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = version).execute()
    }

    private fun variant(catalog: CatalogId) = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog))

    private fun model(nodeId: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            nodeId to Node(id = nodeId, type = "text"),
        ),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf(nodeId))),
        themeRef = ThemeRef.Inherit,
    )

    private fun tenantOf(catalog: CatalogId): Tenant = withMediator { GetTenant(catalog.tenantKey).query()!! }

    private fun authoredCatalog(slug: String): CatalogId = catalogIn(createTenant(slug).id, slug)

    private fun catalogIn(tenant: TenantKey, slug: String): CatalogId {
        val key = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant, id = key, name = slug).execute() }
        return CatalogId(key, TenantId(tenant))
    }
}
