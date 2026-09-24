// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.DeleteAsset
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
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
import app.epistola.suite.templates.commands.CreateDocumentTemplate
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
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The compatibility surface of ADR 0026 §4: **rebuilding a release from what it retained reproduces
 * the fingerprint it was cut with.**
 *
 * Nothing else checks that the stored shape is faithful. A template's variant models are lifted into
 * revisions of their own and substituted back on the way out; the catalog's own metadata comes from
 * the manifest snapshot rather than the live row; binaries are read through what the release held
 * rather than through whatever asset carries that name now. Any of those losing or changing a byte
 * shows up here as a fingerprint that no longer matches, and nowhere else until an export or a
 * render is wrong in front of someone.
 */
class ReleaseRoundTripTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var assembler: ReleaseContentAssembler

    @Autowired
    private lateinit var fingerprintService: CatalogFingerprintService

    @Test
    fun `a released catalog rebuilds to the fingerprint it was released with`() {
        val catalog = authoredCatalog("rt-basic")

        val released = withMediator {
            seedResources(catalog)
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        val rebuilt = assembler.assemble(catalog.tenantKey, catalog.key, "1.0.0")
        assertThat(rebuilt).`as`("the release retained its content").isNotNull()
        assertThat(fingerprintService.fingerprint(rebuilt!!.content)).isEqualTo(released.fingerprint)
    }

    @Test
    fun `the working copy moving on does not change what an earlier release rebuilds to`() {
        val catalog = authoredCatalog("rt-frozen")

        val (image, first) = withMediator {
            val seeded = seedResources(catalog)
            seeded to ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }

        // Everything the release named, changed or gone: the theme edited, the image deleted, the
        // template's model replaced. None of it may reach 1.0.0.
        val second = withMediator {
            UpdateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand, revised").execute()
            DeleteAsset(catalog.tenantKey, image).execute()
            val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog))
            UpdateDraft(variant, model("changed")).execute()
            PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "2.0.0").execute()
        }

        assertThat(second.fingerprint).`as`("the working copy really did change").isNotEqualTo(first.fingerprint)

        val rebuiltFirst = assembler.assemble(catalog.tenantKey, catalog.key, "1.0.0")
        assertThat(fingerprintService.fingerprint(rebuiltFirst!!.content)).isEqualTo(first.fingerprint)

        val rebuiltSecond = assembler.assemble(catalog.tenantKey, catalog.key, "2.0.0")
        assertThat(fingerprintService.fingerprint(rebuiltSecond!!.content)).isEqualTo(second.fingerprint)
    }

    @Test
    fun `a release that retained nothing rebuilds to nothing, rather than to the working copy`() {
        val catalog = authoredCatalog("rt-none")

        withMediator {
            seedResources(catalog)
            ReleaseCatalogVersion(tenantKey = catalog.tenantKey, catalogKey = catalog.key, version = "1.0.0").execute()
        }
        // A release cut before V20260923201010 has no entries and its content was never retained.
        jdbi.forgetReleaseEntries(catalog.tenantKey, catalog.key)

        assertThat(assembler.assemble(catalog.tenantKey, catalog.key, "1.0.0")).isNull()
        assertThat(assembler.assembleLatest(catalog.tenantKey, catalog.key)).isNull()
    }

    /** A theme, an image and a template with a published model — the shapes that can lose bytes. */
    private fun seedResources(catalog: CatalogId): AssetKey {
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalog), name = "Brand").execute()
        val image = UploadAsset(
            tenantId = catalog.tenantKey,
            name = "logo.png",
            mediaType = AssetMediaType.PNG,
            content = ByteArray(120) { ((it * 11) % 256).toByte() },
            width = 1,
            height = 1,
            catalogKey = catalog.key,
        ).execute().id
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalog))
        CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
        UpdateDraft(variant, model("original")).execute()
        PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
        return image
    }

    /** Two models that differ, so "the working copy moved on" is a real difference in the bytes. */
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

    private fun authoredCatalog(slug: String): CatalogId {
        val tenant = createTenant(slug)
        val catalogKey = CatalogKey.of(slug)
        withMediator { CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = slug).execute() }
        return CatalogId(catalogKey, TenantId(tenant.id))
    }
}
