// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A release received beside the others (ADR 0032) may bring an image no version of the catalog has,
 * so no asset of the tenant holds its bytes. The release keeps them anyway, so its content can be
 * rebuilt, exported and merged.
 */
class ReleaseReceivedBesideBinariesTest : IntegrationTestBase() {

    @Autowired private lateinit var assembler: ReleaseContentAssembler

    @Test
    fun `a release received beside the others keeps the bytes of an image it brings`() {
        val a = createTenant("Beside Bytes A").id
        val b = createTenant("Beside Bytes B").id
        val catalog = CatalogKey.of("beside-bytes")
        val theme = { tenant: app.epistola.suite.common.ids.TenantKey -> ThemeId(ThemeKey.of("tha"), CatalogId(catalog, TenantId(tenant))) }
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "Beside bytes").execute()
            CreateTheme(id = theme(a), name = "base").execute()
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogDraft(tenantKey = a, catalogKey = catalog, version = "1.0.0").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = ExportCatalogZip(a, catalog, "1.0.0").execute().zipBytes).execute()
            UploadAsset(b, "Mark", AssetMediaType.PNG, PNG, 1, 1, catalog).execute()
            MarkCatalogReady(b, catalog).execute()
            TestNamespaces.share(a, b).execute()
            ReleaseCatalogDraft(tenantKey = b, catalogKey = catalog, version = "2.0.0").execute()
            val v2 = ExportCatalogZip(b, catalog, "2.0.0").execute().zipBytes

            UpdateTheme(theme(a), name = "A's own").execute()
            ImportCatalogZip(tenantKey = a, zipBytes = v2).execute()

            val content = assembler.assemble(a, catalog, "2.0.0")!!.content
            val image = content.resourceDetails.values.map { it.resource }.filterIsInstance<ImageResource>().single()
            assertThat(content.assetContents[image.contentPath()]).`as`("the image's bytes").isEqualTo(PNG)
        }
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        )
    }
}
