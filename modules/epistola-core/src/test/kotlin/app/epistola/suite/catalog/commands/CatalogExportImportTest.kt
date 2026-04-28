package app.epistola.suite.catalog.commands

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val DEMO_CATALOG_URL = "classpath:demo/catalog/catalog.json"

class CatalogExportImportTest : IntegrationTestBase() {

    @Test
    fun `export and import round-trip preserves all resources`() {
        val tenant = createTenant("Round-Trip Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val catalogKey = CatalogKey.of("roundtrip-catalog")
        val catalogId = CatalogId(catalogKey, tenantId)

        withMediator {
            // Create an authored catalog
            CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "Round-Trip Catalog",
                description = "Test catalog for export/import round-trip",
            ).execute()

            // Create a template
            val templateKey = TestIdHelpers.nextTemplateId()
            CreateDocumentTemplate(
                id = TemplateId(templateKey, catalogId),
                name = "Round-Trip Template",
            ).execute()

            // Create a theme
            val themeKey = ThemeKey.of("roundtrip-theme")
            CreateTheme(
                id = ThemeId(themeKey, catalogId),
                name = "Round-Trip Theme",
                description = "A test theme",
            ).execute()

            // Assign theme to template
            val templateId = TemplateId(templateKey, catalogId)
            UpdateDocumentTemplate(
                id = templateId,
                themeId = themeKey,
                themeCatalogKey = catalogKey,
            ).execute()

            // Create an attribute
            val attrKey = AttributeKey.of("roundtrip-attr")
            CreateAttributeDefinition(
                id = AttributeId(attrKey, catalogId),
                displayName = "Round-Trip Attribute",
                allowedValues = listOf("value-a", "value-b"),
            ).execute()

            // Add a contract with schema and publish it
            val contractSchema = ObjectMapper().readValue(
                """{"type":"object","properties":{"name":{"type":"string"},"amount":{"type":"number"}},"required":["name"]}""",
                ObjectNode::class.java,
            )
            UpdateContractVersion(
                templateId = templateId,
                dataModel = contractSchema,
            ).execute()
            PublishContractVersion(templateId = templateId).execute()

            // Publish the default template version so it gets included in the export
            val defaultVariantKey = VariantKey.of("${templateKey.value}-default")
            val defaultVariantId = VariantId(defaultVariantKey, templateId)
            val defaultVersionId = VersionId(VersionKey.of(1), defaultVariantId)
            PublishVersion(versionId = defaultVersionId).execute()

            // Upload an asset (small PNG stub)
            val pngBytes = createMinimalPng()
            UploadAsset(
                tenantId = tenantKey,
                name = "test-logo",
                mediaType = AssetMediaType.PNG,
                content = pngBytes,
                width = 1,
                height = 1,
                catalogKey = catalogKey,
            ).execute()

            // Export the catalog as ZIP
            val exportResult = ExportCatalogZip(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
            ).execute()

            assertThat(exportResult.zipBytes).isNotEmpty()
            assertThat(exportResult.filename).contains("roundtrip-catalog")

            // Import the ZIP into a NEW catalog
            val importResult = ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = exportResult.zipBytes,
                catalogType = CatalogType.AUTHORED,
            ).execute()

            // The import creates the catalog from the manifest slug, which is the same as the original.
            // Since it already exists as AUTHORED, it updates in place.
            assertThat(importResult.catalogKey).isEqualTo(catalogKey)

            // Verify resources were imported
            val resourceTypes = importResult.results.map { it.type }.toSet()
            assertThat(resourceTypes).contains("template", "theme", "attribute", "asset")
            assertThat(importResult.results).allSatisfy { result ->
                assertThat(result.status).isNotEqualTo(InstallStatus.FAILED)
            }

            // Verify the catalog still exists with all resources via browse
            val browseResult = BrowseCatalog(
                tenantKey = tenantKey,
                catalogKey = catalogKey,
            ).query()

            assertThat(browseResult.resources).isNotEmpty()

            // Verify theme reference survived the round-trip
            val reimportedTemplate = GetDocumentTemplate(templateId).query()
            assertThat(reimportedTemplate).isNotNull
            assertThat(reimportedTemplate!!.themeKey).isEqualTo(themeKey)
            assertThat(reimportedTemplate.themeCatalogKey).isEqualTo(catalogKey)

            // Verify contract data survived the round-trip
            val reimportedContract = GetLatestContractVersion(templateId = templateId).query()
            assertThat(reimportedContract).isNotNull
            assertThat(reimportedContract!!.dataModel).isNotNull
            val properties = reimportedContract.dataModel!!.get("properties")
            assertThat(properties.has("name")).isTrue()
            assertThat(properties.has("amount")).isTrue()
        }
    }

    @Test
    fun `import into existing subscribed catalog is rejected`() {
        val tenant = createTenant("Subscribed Reject")
        val tenantKey = tenant.id

        withMediator {
            // Register a subscribed catalog (from demo)
            RegisterCatalog(
                tenantKey = tenantKey,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            // Export the demo catalog to get a valid ZIP
            val exportResult = ExportCatalogZip(
                tenantKey = tenantKey,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            // Try to import with the same slug into the subscribed catalog
            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = exportResult.zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("subscribed")
        }
    }

    @Test
    fun `import ZIP without catalog json is rejected`() {
        val tenant = createTenant("No Manifest")
        val tenantKey = tenant.id

        withMediator {
            // Create a ZIP with no catalog.json
            val zipBytes = createZipWithoutManifest()

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("catalog.json")
        }
    }

    @Test
    fun `requireCatalogEditable passes for authored and throws for subscribed`() {
        val tenant = createTenant("Editable Test")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)

        withMediator {
            // Create an authored catalog
            val authoredKey = CatalogKey.of("editable-test")
            CreateCatalog(
                tenantKey = tenantKey,
                id = authoredKey,
                name = "Editable Catalog",
            ).execute()

            // Should NOT throw for authored catalog
            requireCatalogEditable(tenantKey, authoredKey)

            // Register a subscribed catalog
            RegisterCatalog(
                tenantKey = tenantKey,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            val subscribedKey = CatalogKey.of("epistola-demo")

            // Should throw for subscribed catalog
            assertThatThrownBy {
                requireCatalogEditable(tenantKey, subscribedKey)
            }.isInstanceOf(CatalogReadOnlyException::class.java)
                .hasMessageContaining("read-only")

            // Inside CatalogImportContext.runAsImport, even subscribed should pass
            CatalogImportContext.runAsImport {
                requireCatalogEditable(tenantKey, subscribedKey)
                // No exception — import context bypasses the check
            }
        }
    }

    @Test
    fun `create catalog and verify via get catalog`() {
        val tenant = createTenant("Create Catalog")
        val tenantKey = tenant.id

        withMediator {
            val catalogKey = CatalogKey.of("test-catalog")
            val catalog = CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "Test Catalog",
                description = "A test catalog",
            ).execute()

            assertThat(catalog.id).isEqualTo(catalogKey)
            assertThat(catalog.name).isEqualTo("Test Catalog")
            assertThat(catalog.description).isEqualTo("A test catalog")
            assertThat(catalog.type).isEqualTo(CatalogType.AUTHORED)

            // Verify via GetCatalog
            val fetched = GetCatalog(tenantKey, catalogKey).query()
            assertThat(fetched).isNotNull
            assertThat(fetched!!.id).isEqualTo(catalogKey)
            assertThat(fetched.name).isEqualTo("Test Catalog")
            assertThat(fetched.type).isEqualTo(CatalogType.AUTHORED)
        }
    }

    @Test
    fun `create duplicate catalog throws`() {
        val tenant = createTenant("Duplicate Catalog")
        val tenantKey = tenant.id

        withMediator {
            val catalogKey = CatalogKey.of("dup-catalog")
            CreateCatalog(
                tenantKey = tenantKey,
                id = catalogKey,
                name = "First Catalog",
            ).execute()

            assertThatThrownBy {
                CreateCatalog(
                    tenantKey = tenantKey,
                    id = catalogKey,
                    name = "Duplicate Catalog",
                ).execute()
            }.isInstanceOf(Exception::class.java)
        }
    }

    /**
     * Creates a minimal valid 1x1 PNG image (67 bytes).
     */
    private fun createMinimalPng(): ByteArray {
        val header = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A, // PNG signature
        )
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D, // chunk length = 13
            0x49, 0x48, 0x44, 0x52, // "IHDR"
            0x00, 0x00, 0x00, 0x01, // width = 1
            0x00, 0x00, 0x00, 0x01, // height = 1
            0x08, 0x02, // bit depth = 8, color type = 2 (RGB)
            0x00, 0x00, 0x00, // compression, filter, interlace
            0x90.toByte(), 0x77, 0x53, 0xDE.toByte(), // CRC
        )
        val idat = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C, // chunk length = 12
            0x49, 0x44, 0x41, 0x54, // "IDAT"
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x02, 0x00, 0x01, // compressed data
            0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33, // CRC
        )
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // chunk length = 0
            0x49, 0x45, 0x4E, 0x44, // "IEND"
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(), // CRC
        )
        return header + ihdr + idat + iend
    }

    /**
     * Creates a ZIP archive that does NOT contain catalog.json.
     */
    private fun createZipWithoutManifest(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("some-other-file.txt"))
            zip.write("not a catalog".toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }
}
