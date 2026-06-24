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
import org.assertj.core.api.Assertions.assertThat
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

    private val depKey = CatalogKey.of("dep-test")

    /** Mutable `file://` copy of the self-contained fixture. */
    private fun copyFixture(tmp: Path): String {
        val src = javaClass.classLoader.getResource("test-catalogs/dependency-test/catalog.json")!!.toURI().toPath().parent
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
            (manifest.get("resources") as ArrayNode).forEach { if (it.get("slug").asString() != slug) kept.add(it) }
            manifest.set("resources", kept)
        }
    }

    @Test
    fun `freshly registered catalog previews as no changes`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview NoChange")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            assertThat(InstallFromCatalog(tenantKey = tenant.id, catalogKey = depKey).execute().filter { it.status == InstallStatus.FAILED }).isEmpty()

            val diff = PreviewCatalogUpgrade(tenant.id, depKey).query()

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
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = depKey).execute()

            // Publisher edits the theme after we registered.
            rewriteJson(tmp.resolve("resources/themes/test-theme.json")) { detail ->
                (detail.get("resource") as ObjectNode).put("name", "Renamed By Publisher")
            }

            val diff = PreviewCatalogUpgrade(tenant.id, depKey).query()

            assertThat(diff.changed).containsExactly(UpgradeResourceChange("theme", "test-theme"))
            assertThat(diff.added).isEmpty()
            assertThat(diff.removed).isEmpty()
            assertThat(diff.unchanged).hasSize(5)
            assertThat(diff.conflicts).isEmpty()
            assertThat(diff.hasChanges).isTrue()
        }
    }

    @Test
    fun `manifest-dropped resource previews as REMOVED`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        val tenant = createTenant("Preview Removed")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = depKey).execute()

            removeResource(tmp, "no-deps") // publisher pulled a template

            val diff = PreviewCatalogUpgrade(tenant.id, depKey).query()

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
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = depKey).execute()

            // Publisher ships a brand-new theme in the next release.
            Files.write(
                tmp.resolve("resources/themes/extra-theme.json"),
                """{"schemaVersion":2,"resource":{"type":"theme","slug":"extra-theme","name":"Extra Theme","documentStyles":{"fontFamily":"Georgia, serif","fontSize":"12pt"}}}""".toByteArray(),
            )
            rewriteJson(tmp.resolve("catalog.json")) { manifest ->
                (manifest.get("resources") as ArrayNode).add(
                    objectMapper.createObjectNode()
                        .put("type", "theme")
                        .put("slug", "extra-theme")
                        .put("name", "Extra Theme")
                        .put("detailUrl", "./resources/themes/extra-theme.json"),
                )
            }

            val diff = PreviewCatalogUpgrade(tenant.id, depKey).query()

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
        val defaultCatalogId = CatalogId(CatalogKey.DEFAULT, TenantId(tenant.id))

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = depKey).execute()

            // A template in another catalog pins the fixture's theme.
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), defaultCatalogId)
            CreateDocumentTemplate(id = templateId, name = "Cross-Ref Template").execute()
            UpdateDocumentTemplate(id = templateId, themeId = ThemeKey.of("test-theme"), themeCatalogKey = depKey).execute()

            // Publisher removes that theme in the next release.
            removeResource(tmp, "test-theme")

            val diff = PreviewCatalogUpgrade(tenant.id, depKey).query()

            assertThat(diff.removed).contains(UpgradeResourceChange("theme", "test-theme"))
            assertThat(diff.hasConflicts).isTrue()
            assertThat(diff.conflicts).anyMatch { it.contains("test-theme") && it.contains("Cross-Ref Template") }
        }
    }

    @Test
    fun `subscribed source on an older schema is reported out of sync`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp)
        rewriteJson(tmp.resolve("catalog.json")) { it.put("schemaVersion", 2) } // publisher on an older Epistola
        val tenant = createTenant("Schema Behind")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()

            val status = CheckCatalogUpgrade(tenant.id, depKey).query()

            assertThat(status.sourceSchemaVersion).isEqualTo(2)
            assertThat(status.currentSchemaVersion).isEqualTo(CATALOG_SCHEMA_VERSION)
            assertThat(status.schemaSyncState).isEqualTo(CatalogSchemaSyncState.SOURCE_BEHIND)
        }
    }

    @Test
    fun `subscribed source on the current schema is in sync`(@TempDir tmp: Path) {
        val sourceUrl = copyFixture(tmp) // fixture manifest is at the current schema version
        val tenant = createTenant("Schema InSync")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()

            val status = CheckCatalogUpgrade(tenant.id, depKey).query()

            assertThat(status.sourceSchemaVersion).isEqualTo(CATALOG_SCHEMA_VERSION)
            assertThat(status.schemaSyncState).isEqualTo(CatalogSchemaSyncState.IN_SYNC)
        }
    }

    @Test
    fun `unknown catalog throws CatalogNotFoundException (maps to 404, not a 500)`() {
        val tenant = createTenant("Preview Missing")
        withMediator {
            assertThrows<CatalogNotFoundException> {
                PreviewCatalogUpgrade(tenant.id, CatalogKey.of("no-such-catalog")).query()
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
                PreviewCatalogUpgrade(tenant.id, key).query()
            }
            assertThat(ex.catalogKey).isEqualTo(key)
        }
    }
}
