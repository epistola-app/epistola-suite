// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.zip.ZipInputStream

class ExportUsesReleasedVersionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun exportedManifest(zipBytes: ByteArray): CatalogManifest {
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "catalog.json") {
                    return objectMapper.readValue(zip.readBytes(), CatalogManifest::class.java)
                }
                entry = zip.nextEntry
            }
        }
        error("catalog.json not found in export")
    }

    @Test
    fun `export version reflects release state and fingerprint matches bytes`() {
        val tenant = createTenant("Export Version Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("exp-cat")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = catalogKey, name = "Exp Cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("tha"), catalogId), name = "Th").execute()

            // 1. Never released -> 0.0.0-dev, fingerprint present
            val pre = exportedManifest(ExportCatalogZip(tenantKey, catalogKey).execute().zipBytes)
            assertThat(pre.release.version).isEqualTo("0.0.0-dev")
            assertThat(pre.release.fingerprint).isNotNull().hasSize(64)

            // 2. Released, no edits -> clean version, fingerprint == released fingerprint
            val released = ReleaseCatalogVersion(tenantKey, catalogKey, "1.0.0").execute()
            val clean = exportedManifest(ExportCatalogZip(tenantKey, catalogKey).execute().zipBytes)
            assertThat(clean.release.version).isEqualTo("1.0.0")
            assertThat(clean.release.fingerprint).isEqualTo(released.fingerprint)

            // 3. Edit after release -> <version>-dev, fingerprint differs from released
            CreateTheme(id = ThemeId(ThemeKey.of("th2"), catalogId), name = "Th2").execute()
            val drifted = exportedManifest(ExportCatalogZip(tenantKey, catalogKey).execute().zipBytes)
            assertThat(drifted.release.version).isEqualTo("1.0.0-dev")
            assertThat(drifted.release.fingerprint).isNotEqualTo(released.fingerprint)
        }
    }
}
