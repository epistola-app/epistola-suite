// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CATALOG_SCHEMA_VERSION
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogNotUpgradeableException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.toPath

/**
 * Source-vs-source preview: the baseline is the per-resource source digests
 * captured at [RegisterCatalog]; preview re-fetches the (mutated) source and
 * diffs against it. So changes here mean the *publisher* changed something —
 * the fixture is a mutable `file://` copy we edit between register and preview.
 */
class PreviewCatalogUpgradeTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    private val depKey = CatalogKey.of("dep-test")

    /** Mutable `file://` copy of the self-contained fixture. */
    private fun copyFixture(tmp: Path): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = tmp.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return tmp.resolve("catalog.json").toUri().toString()
    }

    private fun rewriteJson(path: Path, mutate: (ObjectNode) -> Unit) {
        val node = objectMapper.readTree(Files.readAllBytes(path)) as ObjectNode
        mutate(node)
        Files.write(path, objectMapper.writeValueAsBytes(node))
    }

    /** Drop a resource entry from the manifest and delete its detail file. */
    private fun removeResource(tmp: Path, slug: String) {
        rewriteJson(tmp.resolve("catalog.json")) { manifest ->
            val kept = objectMapper.createArrayNode()
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("key").asString() != slug) kept.add(it) }
            manifest.set("resources", kept)
        }
    }

    @Test
    fun `freshly registered catalog previews as no changes`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview NoChange")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            assertThat(InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute().filter { it.status == InstallStatus.FAILED }).isEmpty()

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.hasChanges).isFalse()
            assertThat(diff.added).isEmpty()
            assertThat(diff.removed).isEmpty()
            assertThat(diff.changed).isEmpty()
            assertThat(diff.unchanged).hasSize(6)
            assertThat(diff.conflicts).isEmpty()
            assertThat(diff.newVersion).isEqualTo(diff.previousVersion)
        }
    }

    @Test
    fun `publisher-changed resource previews as CHANGED`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Changed")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            // Publisher edits the theme after we registered.
            rewriteJson(tmp.resolve("resources/themes/test-theme.json")) { detail ->
                (detail.get("resource") as ObjectNode).put("name", "Renamed By Publisher")
            }

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.changed).containsExactly(UpgradeResourceChange("theme", "test-theme"))
            assertThat(diff.added).isEmpty()
            assertThat(diff.removed).isEmpty()
            assertThat(diff.unchanged).hasSize(5)
            assertThat(diff.conflicts).isEmpty()
            assertThat(diff.hasChanges).isTrue()
        }
    }

    @Test
    fun `publisher-changed metadata previews as catalog changes`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Metadata")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            // Its next release: one number names one release.
            rewriteJson(tmp.resolve("catalog.json")) { manifest ->
                (manifest.get("release") as ObjectNode).put("version", "1.1.0")
                (manifest.get("catalog") as ObjectNode)
                    .put("name", "Publisher Renamed Catalog")
                    .set("keywords", objectMapper.createArrayNode().add("new-keyword"))
            }

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.added).isEmpty()
            assertThat(diff.removed).isEmpty()
            assertThat(diff.changed).isEmpty()
            assertThat(diff.metadataChanges)
                .containsExactly(CatalogMetadataSection.IDENTITY, CatalogMetadataSection.KEYWORDS)
            assertThat(diff.hasChanges).isTrue()

            UpgradeCatalog(testCatalog(tenant.id, depKey)).execute()
            val upgraded = GetCatalog(testCatalog(tenant.id, depKey)).query()!!
            assertThat(upgraded.name).isEqualTo("Publisher Renamed Catalog")
            assertThat(upgraded.catalogMetadata.keywords).containsExactly("new-keyword")
        }
    }

    @Test
    fun `a template whose only change is its description previews as CHANGED`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Description")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            // Wire v8 added the description; the per-resource V3 form leaves it out.
            rewriteJson(tmp.resolve("resources/templates/no-deps.json")) { detail ->
                (detail.get("resource") as ObjectNode).put("description", "Now described")
            }

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.changed).containsExactly(UpgradeResourceChange("template", "no-deps"))
            assertThat(diff.unchanged).hasSize(5)
        }
    }

    @Test
    fun `the installed release is compared with the source as the incoming one is, and an upgrade leaves nothing to preview`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Legacy Baseline")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()
        }
        withMediator {
            // The source still serves the installed release: nothing changed.
            assertThat(PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query().hasChanges).isFalse()

            // The publisher's next release describes a template and renames the theme.
            rewriteJson(tmp.resolve("resources/templates/no-deps.json")) { detail ->
                (detail.get("resource") as ObjectNode).put("description", "Now described")
            }
            rewriteJson(tmp.resolve("resources/themes/test-theme.json")) { detail ->
                (detail.get("resource") as ObjectNode).put("name", "Renamed By Publisher")
            }
            rewriteJson(tmp.resolve("catalog.json")) { manifest ->
                (manifest.get("release") as ObjectNode).put("version", "1.1.0")
                // The manifest's entry says what the detail says (a listing shows it unopened).
                manifest.get("resources").first { it.get("key").asString() == "no-deps" }.let { (it as ObjectNode).put("description", "Now described") }
                manifest.get("resources").first { it.get("key").asString() == "test-theme" }.let { (it as ObjectNode).put("name", "Renamed By Publisher") }
            }

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()
            assertThat(diff.changed).containsExactlyInAnyOrder(UpgradeResourceChange("template", "no-deps"), UpgradeResourceChange("theme", "test-theme"))
            assertThat(diff.added).isEmpty()
            assertThat(diff.removed).isEmpty()

            UpgradeCatalog(testCatalog(tenant.id, depKey)).execute()
            val upgraded = GetCatalog(testCatalog(tenant.id, depKey)).query()!!
            assertThat(upgraded.installedReleaseVersion).isEqualTo("1.1.0")
            assertThat(PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query().hasChanges).isFalse()
        }
    }

    @Test
    fun `manifest-dropped resource previews as REMOVED`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Removed")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            removeResource(tmp, "no-deps") // publisher pulled a template

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.removed).containsExactly(UpgradeResourceChange("template", "no-deps"))
            assertThat(diff.added).isEmpty()
            assertThat(diff.changed).isEmpty()
            assertThat(diff.conflicts).isEmpty()
        }
    }

    @Test
    fun `manifest-added resource previews as ADDED`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Added")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            // Publisher ships a brand-new theme in the next release.
            Files.write(
                tmp.resolve("resources/themes/extra-theme.json"),
                """{"schemaVersion":$CATALOG_SCHEMA_VERSION,"resource":{"type":"theme","key":"extra-theme","name":"Extra Theme","documentStyles":{"fontFamily":"Georgia, serif","fontSize":"12pt"}}}""".toByteArray(),
            )
            rewriteJson(tmp.resolve("catalog.json")) { manifest ->
                (manifest.get("resources") as ArrayNode).add(
                    objectMapper.createObjectNode()
                        .put("type", "theme")
                        .put("key", "extra-theme")
                        .put("name", "Extra Theme")
                        .put("detailUrl", "./resources/themes/extra-theme.json"),
                )
            }

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.added).containsExactly(UpgradeResourceChange("theme", "extra-theme"))
            assertThat(diff.removed).isEmpty()
            assertThat(diff.changed).isEmpty()
            assertThat(diff.unchanged).hasSize(6)
        }
    }

    @Test
    fun `preview surfaces a cross-catalog conflict before apply`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Conflict")
        val defaultCatalogId = testCatalog(TenantId(tenant.id), CatalogKey.DEFAULT)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute()

            // A template in another catalog pins the fixture's theme.
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), defaultCatalogId)
            CreateDocumentTemplate(id = templateId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(id = templateId, themeId = ThemeKey.of("test-theme"), themeCatalog = testCatalog(templateId.tenantKey, depKey)).execute()

            // Publisher removes that theme in the next release.
            removeResource(tmp, "test-theme")

            val diff = PreviewCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(diff.removed).contains(UpgradeResourceChange("theme", "test-theme"))
            assertThat(diff.hasConflicts).isTrue()
            assertThat(diff.conflicts).anyMatch { it.contains("test-theme") && it.contains("Cross-Ref Template") }
        }
    }

    @Test
    fun `registration rejects a source on an older schema`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        rewriteJson(tmp.resolve("catalog.json")) { it.put("schemaVersion", 2) } // publisher on an older Epistola
        val tenant = createTenant("Schema Behind")

        withMediator {
            assertThrows<CatalogSchemaTooOldException> {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            }
        }
    }

    @Test
    fun `subscribed source on the current schema is in sync`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp) // fixture manifest is at the current schema version
        val tenant = createTenant("Schema InSync")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()

            val status = CheckCatalogUpgrade(testCatalog(tenant.id, depKey)).query()

            assertThat(status.sourceSchemaVersion).isEqualTo(CATALOG_SCHEMA_VERSION)
            assertThat(status.schemaSyncState).isEqualTo(CatalogSchemaSyncState.IN_SYNC)
        }
    }

    @Test
    fun `unknown catalog throws CatalogNotFoundException (maps to 404, not a 500)`() {
        val tenant = createTenant("Preview Missing")
        withMediator {
            assertThrows<CatalogNotFoundException> {
                PreviewCatalogUpgrade(testCatalog(tenant.id, CatalogKey.of("no-such-catalog"))).query()
            }
        }
    }

    @Test
    fun `AUTHORED catalog (no source URL) throws CatalogNotUpgradeableException (maps to 409, not a 500)`() {
        val tenant = createTenant("Preview Authored")
        val key = CatalogKey.of("authored-cat")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Authored Cat").execute()

            val ex = assertThrows<CatalogNotUpgradeableException> {
                PreviewCatalogUpgrade(testCatalog(tenant.id, key)).query()
            }
            assertThat(ex.catalogKey).isEqualTo(key)
        }
    }
}
