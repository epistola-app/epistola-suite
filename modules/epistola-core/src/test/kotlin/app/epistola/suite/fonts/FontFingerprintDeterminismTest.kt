// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontIntegrityException
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.templates.queries.versions.GetLatestPublishedVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The headline guarantee: a *published* template version renders
 * deterministically-or-not-at-all with respect to font bytes.
 *
 * Flow: AUTHORED-catalog font referenced by a theme used by a template →
 * publish (the snapshot pins the family fingerprint) → the published render
 * succeeds. Then a face is re-uploaded with *different bytes* under the same
 * slug/weight/italic → the published render now fails loudly with
 * [FontIntegrityException] (the live fingerprint no longer matches the pin),
 * while a fresh *draft* render (pins nothing) still works. Re-publishing
 * re-pins the new bytes and the published render succeeds again.
 */
@Timeout(60)
class FontFingerprintDeterminismTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private val objectMapper = ObjectMapper()

    private fun bytesOf(resource: String): ByteArray = resourceLoader
        .getResource("classpath:$resource")
        .contentAsByteArray

    private fun upload(tenant: TenantId, name: String, resource: String): AssetKey = UploadAsset(
        tenantId = tenant.key,
        name = name,
        mediaType = AssetMediaType.TTF,
        content = bytesOf(resource),
        width = null,
        height = null,
        catalogKey = CatalogKey.DEFAULT,
    ).execute().id

    private fun importBrandFont(tenant: TenantId, asset: AssetKey) {
        ImportFont(
            tenantId = tenant,
            catalogKey = CatalogKey.DEFAULT,
            slug = "brand-sans",
            name = "Brand Sans",
            kind = FontKind.SANS.wire,
            variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = asset)),
        ).execute()
    }

    private fun templateModel(): app.epistola.template.model.TemplateDocument {
        val rootId = "root-fp"
        val rootSlot = "root-slot-fp"
        val rtvId = "rtv-fp"
        return app.epistola.template.model.TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(
                rootId to app.epistola.template.model.Node(id = rootId, type = "root", slots = listOf(rootSlot)),
                rtvId to app.epistola.template.model.Node(
                    id = rtvId,
                    type = "richTextVariable",
                    slots = emptyList(),
                    props = mapOf("binding" to "body"),
                ),
            ),
            slots = mapOf(
                rootSlot to app.epistola.template.model.Slot(
                    id = rootSlot,
                    nodeId = rootId,
                    name = "children",
                    children = listOf(rtvId),
                ),
            ),
            themeRef = app.epistola.template.model.ThemeRef.Inherit,
        )
    }

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var releaseRenderSource: app.epistola.suite.generation.release.ReleaseRenderSource

    /** The regular brand-sans face exactly as release [version] of the default catalog renders it. */
    private fun releaseFace(tenant: app.epistola.suite.common.ids.TenantKey, version: String): ByteArray? {
        val tenantRow = app.epistola.suite.tenants.queries.GetTenant(tenant).query()!!
        val template = jdbiForTest.withHandle<String, Exception> { h ->
            h.createQuery("SELECT resource_key FROM release_entries WHERE tenant_key = :t AND version = :v AND resource_type = 'template'")
                .bind("t", tenant).bind("v", version).mapTo(String::class.java).one()
        }
        val inputs = releaseRenderSource.resolve(
            tenantRow,
            app.epistola.suite.generation.release.ReleaseRef(CatalogKey.DEFAULT, version),
            template,
            VariantKey.INITIAL.value,
        )
        return inputs.fontFamilyResolver.resolve("default", "brand-sans", 400, false)
    }

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var jdbiForTest: org.jdbi.v3.core.Jdbi

    private fun previewData(): ObjectNode = objectMapper.readValue(
        """
        {
          "body": {
            "type": "doc",
            "content": [
              { "type": "paragraph", "content": [{ "type": "text", "text": "Deterministic glyphs." }] }
            ]
          }
        }
        """.trimIndent(),
        ObjectNode::class.java,
    )

    @Test
    fun `a release keeps rendering with the font bytes it was cut with`() {
        withMediator {
            val tenant = createTenant("FP Determinism Tenant")
            val tenantId = TenantId(tenant.id)
            val catalogId = CatalogId.default(tenantId)

            // 1. AUTHORED-catalog font (Inter Regular bytes), referenced by a theme.
            val originalAsset = upload(tenantId, "brand-v1.ttf", "epistola/fonts/inter/inter-Regular.ttf")
            importBrandFont(tenantId, originalAsset)

            CreateTheme(
                id = ThemeId(ThemeKey.of("brand-theme"), catalogId),
                name = "Brand Theme",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("slug" to "brand-sans", "catalogKey" to "default"),
                ),
            ).execute()

            // 2. Template pinned to that theme + a richtext contract.
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = "fp-determinism-template").execute().withRequiredDataExample()
            UpdateDocumentTemplate(
                id = templateId,
                themeId = ThemeKey.of("brand-theme"),
                themeCatalogKey = CatalogKey.DEFAULT,
            ).execute()

            val variantId = VariantId(VariantKey.INITIAL, templateId)
            UpdateDraft(variantId = variantId, templateModel = templateModel()).execute()
            UpdateContractVersion(
                templateId = templateId,
                dataModel = objectMapper.readValue(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "body": { "${"$"}ref": "https://epistola.app/schemas/richtext-block-v1.json" }
                      }
                    }
                    """.trimIndent(),
                    ObjectNode::class.java,
                ),
            ).execute()

            // 3. Publish — the snapshot pins the brand-sans family fingerprint.
            val draft = GetDraft(variantId).query()!!
            val published = PublishVersion(VersionId(draft.id, variantId)).execute()!!
            assertThat(published.resolvedTheme).isNotNull()
            assertThat(published.resolvedTheme!!.fontFingerprints).isNotEmpty()

            // 4. Released, and the release renders.
            mediator.releaseNext(catalogId)
            val ok = PreviewDocument(
                tenantId = tenant.id,
                catalogKey = CatalogKey.DEFAULT,
                templateId = templateId.key,
                data = previewData(),
            ).query()
            assertThat(String(ok.copyOfRange(0, 4))).isEqualTo("%PDF")
            val regular = javaClass.classLoader.getResourceAsStream("epistola/fonts/inter/inter-Regular.ttf")!!.readBytes()
            assertThat(releaseFace(tenant.id, "1.0.0")).isEqualTo(regular)

            // 5. Replace the face with DIFFERENT bytes (Inter Bold), same slug/weight/italic.
            val mutatedAsset = upload(tenantId, "brand-v2.ttf", "epistola/fonts/inter/inter-Bold.ttf")
            importBrandFont(tenantId, mutatedAsset)

            // 6. The release still renders, with the bytes it kept: replacing a face in the working
            //    copy does not reach a release.
            val stillOk = PreviewDocument(
                tenantId = tenant.id,
                catalogKey = CatalogKey.DEFAULT,
                templateId = templateId.key,
                data = previewData(),
            ).query()
            assertThat(String(stillOk.copyOfRange(0, 4))).isEqualTo("%PDF")
            assertThat(releaseFace(tenant.id, "1.0.0")).isEqualTo(regular)

            // 7. The next release takes the new bytes.
            mediator.releaseNext(catalogId)
            val bold = javaClass.classLoader.getResourceAsStream("epistola/fonts/inter/inter-Bold.ttf")!!.readBytes()
            assertThat(releaseFace(tenant.id, "1.0.1")).isEqualTo(bold)
        }
    }
}
