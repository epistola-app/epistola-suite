// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogKeys
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withReleaseFingerprint
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A resource key follows the contract's one rule (`CatalogKeys.RESOURCE`) both ways: whatever the
 * contract allows installs and reads here, and whatever is created here can be released.
 */
class ResourceKeyFormatTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a catalog whose theme key starts with a digit, as the contract allows, installs`() {
        val tenant = createTenant("Key starting with a digit").id

        val result = withMediator { ImportCatalogZip(tenantKey = tenant, zipBytes = archiveWithTheme("2024-letter"), provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }

        assertThat(result.aborted).`as`(result.results.joinToString { "${it.slug}: ${it.errorMessage}" }).isFalse()
        val catalog = CatalogId("acme", CatalogKey.of("digits"), TenantId(tenant))
        assertThat(withMediator { ListThemes(TenantId(tenant), catalog = catalog, catalogVersion = null).query().map { it.id.value } }).containsExactly("2024-letter")
    }

    @Test
    fun `what is created under the longest and the leading-digit keys can be released and installed elsewhere`() {
        val tenant = createTenant("Keys released").id
        val receiver = createTenant("Keys received").id
        val key = CatalogKey.of("released-keys")
        val catalog = testCatalog(tenant, key)
        val longest = "a" + "b".repeat(CatalogKeys.MAX_LENGTH - 1)

        val zip = withMediator {
            CreateCatalog(tenantKey = tenant, id = key, name = "Released keys").execute()
            CreateTheme(ThemeId(ThemeKey.of("2024-letter"), catalog), "2024 letter", catalogVersion = null).execute()
            CreateCodeList(CodeListId(CodeListKey.of(longest), catalog), "Longest", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("a", "A")), catalogVersion = null).execute()
            MarkCatalogReady(catalog, catalogVersion = null).execute()
            ReleaseCatalogDraft(catalog = catalog, version = "1.0.0", catalogVersion = null).execute()
            ExportCatalogZip(catalog, "1.0.0").execute().zipBytes
        }
        val received = withMediator { ImportCatalogZip(tenantKey = receiver, zipBytes = zip, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD)).execute() }

        assertThat(received.aborted).isFalse()
        assertThat(withMediator { ListThemes(TenantId(receiver), catalog = received.catalog, catalogVersion = null).query().map { it.id.value } }).containsExactly("2024-letter")
    }

    @Test
    fun `a document can name a template and variant whose keys start with a digit`() {
        // The two key domains a column still uses: generation requests and documents name the template and variant.
        val accepted = jdbi.withHandle<List<String>, Exception> {
            it.createQuery("SELECT CAST('2024-letter' AS template_key) || ' ' || CAST('2024-dutch' AS variant_key)").mapTo(String::class.java).list()
        }

        assertThat(accepted).containsExactly("2024-letter 2024-dutch")
    }

    @Test
    fun `a template keyed with a leading digit is created and read back`() {
        val tenant = createTenant("Template with a digit").id
        val key = CatalogKey.of("digit-templates")
        val id = TemplateId(TemplateKey.of("2024-letter"), testCatalog(tenant, key))

        val created = withMediator {
            CreateCatalog(tenantKey = tenant, id = key, name = "Digit templates").execute()
            CreateDocumentTemplate(id, "2024 letter", catalogVersion = null).execute()
        }

        assertThat(created.id).isEqualTo(id.key)
    }

    /** A wire-v8 archive of catalog `acme/digits` 1.0.0 holding one theme keyed [themeKey]. */
    private fun archiveWithTheme(themeKey: String): ByteArray {
        val manifest = """
            {"schemaVersion":8,
             "catalog":{"registry":"example.org","namespace":"acme","key":"digits","name":"Digits","description":null,"attributes":[],"keywords":[],"presentation":null,"license":null},
             "publisher":{"name":"Test","url":null},
             "release":{"version":"1.0.0","releasedAt":null,"fingerprint":null},
             "compatibility":null,"includes":null,"dependencies":[],
             "resources":[{"type":"theme","key":"$themeKey","name":"Theme","description":null,"updatedAt":null,
                           "detailUrl":"./resources/theme/$themeKey.json"}]}
        """.trimIndent()
        val detail = """{"schemaVersion":8,"resource":{"type":"theme","key":"$themeKey","name":"Theme","documentStyles":{"fontSize":"11pt"}}}"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf("catalog.json" to manifest, "resources/theme/$themeKey.json" to detail).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return withReleaseFingerprint(out.toByteArray())
    }
}
