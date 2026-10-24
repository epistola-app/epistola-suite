// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetLatestCatalogRelease
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
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
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.publishAndRelease
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A patch release (ADR 0034; contract ADR 0003, amendment of 2026-10-22): someone who does not hold a
 * catalog's namespace numbers a release `MAJOR.MINOR.PATCH-<namespace>.<N>`. This installation
 * receives one and orders it as SemVer does, above the release it fixes and below the holder's next,
 * wherever releases are ordered: the catalog's highest release, the latest, the release list; a
 * version range takes one as npm takes a pre-release, only when it names one (contract "Version
 * ranges"). A release number may be up to 128 characters. Issuing one is ADR 0034's
 * phase 5: releasing here still takes `MAJOR.MINOR.PATCH`.
 */
class PatchReleaseTest : IntegrationTestBase() {

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val letters = CatalogKey.of("letters")

    /**
     * In [producer]: `letters` with an invoice released 1.0.0, and the archive of what comes next (a
     * theme added), for the tests to number as the holder or a patcher would.
     */
    private fun produce(producer: TenantKey): Pair<ByteArray, ByteArray> = withMediator {
        val catalog = testCatalog(TenantId(producer), letters)
        CreateCatalog(tenantKey = producer, id = letters, name = "Letters").execute()
        val variant = VariantId(VariantKey.INITIAL, TemplateId(INVOICE, catalog))
        CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
        UpdateVariantContent(variant, TestTemplateBuilder.buildMinimal(name = "Invoice")).execute()
        mediator.publishAndRelease(variant)
        CreateTheme(ThemeId(ThemeKey.of("fix"), catalog), "Fix").execute()
        val next = mediator.releaseNext(catalog)
        ExportCatalogZip(testCatalog(producer, letters), "1.0.0").execute().zipBytes to ExportCatalogZip(testCatalog(producer, letters), next).execute().zipBytes
    }

    /** [zip] numbered [version], as the one who issued that number exports it (an id of its own). */
    private fun numbered(zip: ByteArray, version: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { writer ->
            ZipInputStream(ByteArrayInputStream(zip)).use { reader ->
                var entry = reader.nextEntry
                while (entry != null) {
                    var bytes = reader.readAllBytes()
                    if (entry.name == "catalog.json") {
                        val manifest = objectMapper.readTree(bytes) as ObjectNode
                        (manifest.get("release") as ObjectNode).put("version", version).remove("releaseId")
                        bytes = objectMapper.writeValueAsBytes(manifest)
                    }
                    writer.putNextEntry(ZipEntry(entry.name))
                    writer.write(bytes)
                    writer.closeEntry()
                    entry = reader.nextEntry
                }
            }
        }
        return out.toByteArray()
    }

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogReleaseSummaries(testCatalog(tenant, letters)).query().map { it.version } }

    private fun generate(tenant: TenantKey, selector: String?): String? = withMediator {
        GenerateDocument(
            catalog = testCatalog(tenant, letters),
            templateId = INVOICE,
            variantId = VariantKey.INITIAL,
            releaseVersion = selector,
            environmentId = null,
            data = JsonNodeFactory.instance.objectNode(),
            filename = null,
        ).execute().releaseVersion
    }

    @Test
    fun `a patch release is received and sorts above the release it fixes and below the holder's next`() {
        val producer = createTenant("Patch producer").id
        val receiver = createTenant("Patch receiver").id
        val (v100, v101) = produce(producer)

        withMediator { ImportCatalogZip(tenantKey = receiver, zipBytes = v100).execute() }
        val patch = withMediator { ImportCatalogZip(tenantKey = receiver, zipBytes = numbered(v101, "1.0.1-municipality.1")).execute() }
        assertThat(patch.newlyInstalled).isTrue()

        assertThat(versions(receiver)).containsExactly("1.0.1-municipality.1", "1.0.0")
        withMediator {
            assertThat(GetCatalog(testCatalog(receiver, letters)).query()!!.highestReleaseVersion).isEqualTo("1.0.1-municipality.1")
            assertThat(GetLatestCatalogRelease(testCatalog(receiver, letters)).query().latestVersion).isEqualTo("1.0.1-municipality.1")
        }
        // A range takes a patch release as npm takes a pre-release (contract "Version ranges").
        assertThat(generate(receiver, "^1.0.0")).`as`("a range naming no patch release does not take one").isEqualTo("1.0.0")
        assertThat(generate(receiver, ">=1.0.1-municipality.1")).isEqualTo("1.0.1-municipality.1")
        assertThat(generate(receiver, "1.0.1-municipality.1")).`as`("the exact number").isEqualTo("1.0.1-municipality.1")
        assertThat(generate(receiver, null)).`as`("no range: the highest").isEqualTo("1.0.1-municipality.1")

        // The holder's 1.0.1 supersedes it.
        withMediator { ImportCatalogZip(tenantKey = receiver, zipBytes = numbered(v101, "1.0.1")).execute() }
        assertThat(versions(receiver)).containsExactly("1.0.1", "1.0.1-municipality.1", "1.0.0")
        withMediator { assertThat(GetCatalog(testCatalog(receiver, letters)).query()!!.highestReleaseVersion).isEqualTo("1.0.1") }
        assertThat(generate(receiver, "^1.0.0")).isEqualTo("1.0.1")
        assertThat(generate(receiver, ">=1.0.1-municipality.1 <1.0.1")).isEqualTo("1.0.1-municipality.1")
    }

    @Test
    fun `a release number of up to 128 characters is kept`() {
        val producer = createTenant("Long number producer").id
        val receiver = createTenant("Long number receiver").id
        val (v100, v101) = produce(producer)
        val long = "1.0.1-" + "m" + "a".repeat(62) + ".1"
        assertThat(long.length).isGreaterThan(50)

        withMediator {
            ImportCatalogZip(tenantKey = receiver, zipBytes = v100).execute()
            ImportCatalogZip(tenantKey = receiver, zipBytes = numbered(v101, long)).execute()
        }

        assertThat(versions(receiver)).containsExactly(long, "1.0.0")
    }

    @Test
    fun `releasing here still takes MAJOR_MINOR_PATCH, issuing a patch number is not offered yet`() {
        val producer = createTenant("Patch not issued").id
        produce(producer)
        withMediator {
            assertThatThrownBy { ReleaseCatalogDraft(catalog = testCatalog(producer, letters), version = "1.1.1-municipality.1").execute() }
                .isInstanceOf(CatalogReleaseVersionException::class.java)
                .hasMessageContaining("patch release")
        }
    }

    private companion object {
        val INVOICE: TemplateKey = TemplateKey.of("invoice")
    }
}
