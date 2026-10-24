// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.io.path.writeText

/**
 * A merge of releases that came from a source (ADR 0032, section 5). A release installed from a
 * source URL records its publisher's digests, which need not be what this suite computes for the
 * same content; the merge compares every side by one function over the content each release kept,
 * so what nobody changed is not reported as changed.
 */
class MergeReceivedFromSourceTest : IntegrationTestBase() {

    private val depKey = CatalogKey.of("dep-test")

    /** A writable copy of the dependency-test fixture at [dir], released as [version]; [edit] changes it first. */
    private fun fixture(dir: Path, version: String, edit: (Path) -> Unit = {}): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val manifest = dir.resolve("catalog.json")
        manifest.writeText(manifest.readText().replace("\"version\": \"1.0.0\"", "\"version\": \"$version\""))
        edit(dir)
        return manifest.toUri().toString()
    }

    @Test
    fun `a release received from a source merges with only what each side changed`(@TempDir tmp: Path) {
        val a = createTenant("Merge Source A").id
        val b = createTenant("Merge Source B").id
        val v1 = fixture(tmp.resolve("v1"), "1.0.0")
        withMediator {
            // A installs 1.0.0 from its source.
            RegisterCatalog(tenantKey = a, sourceUrl = v1, authType = AuthType.NONE).execute()
            assertThat(InstallFromCatalog(catalog = testCatalog(a, depKey)).execute().filter { it.status == InstallStatus.FAILED }).isEmpty()
            val v1Fingerprint = ListCatalogReleaseSummaries(testCatalog(a, depKey)).query().single { it.version == "1.0.0" }.fingerprint
            // B installs 1.1.0, which changed the theme and says it was made from 1.0.0, from another.
            val v2 = fixture(tmp.resolve("v2"), "1.1.0") { dir ->
                val theme = dir.resolve("resources/themes/test-theme.json")
                theme.writeText(theme.readText().replace("\"fontSize\": \"11pt\"", "\"fontSize\": \"12pt\""))
                val manifest = dir.resolve("catalog.json")
                manifest.writeText(manifest.readText().replace("\"version\": \"1.1.0\"", "\"version\": \"1.1.0\", \"base\": {\"version\": \"1.0.0\", \"fingerprint\": \"$v1Fingerprint\"}"))
            }
            RegisterCatalog(tenantKey = b, sourceUrl = v2, authType = AuthType.NONE).execute()
            assertThat(InstallFromCatalog(catalog = testCatalog(b, depKey)).execute().filter { it.status == InstallStatus.FAILED }).isEmpty()
            val archive = ExportCatalogZip(testCatalog(b, depKey), "1.1.0").execute().zipBytes

            // A changes a template, so 1.1.0 is received beside its releases, to be merged.
            UpdateDocumentTemplate(id = TemplateId(TemplateKey.of("no-deps"), testCatalog(TenantId(a), depKey)), name = "A's no-deps").execute()
            ImportCatalogZip(tenantKey = a, zipBytes = archive).execute()

            val preview = PreviewDraftMerge(testCatalog(a, depKey), "1.1.0").query()
            assertThat(preview.base).isEqualTo("1.0.0")
            assertThat(preview.resources.filter { it.outcome != MergeOutcome.UNCHANGED }.associate { it.contentKey to it.outcome })
                .containsExactlyInAnyOrderEntriesOf(
                    mapOf(
                        "theme/test-theme" to MergeOutcome.THEIRS,
                        "template/no-deps" to MergeOutcome.OURS,
                    ),
                )
        }
    }
}
