// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.queries.BrowseCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.ResourceStatus
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

class CatalogIntegrationTest : IntegrationTestBase() {

    @Test
    fun `register catalog from classpath creates catalog entity`() {
        val tenant = createTenant("Register Test")

        withMediator {
            val catalog = RegisterCatalog(
                tenantKey = tenant.id,
                sourceUrl = DEMO_CATALOG_URL,
                authType = AuthType.NONE,
            ).execute()

            assertThat(catalog.id).isEqualTo(CatalogKey.of("epistola-demo"))
            assertThat(catalog.name).isEqualTo("Epistola Demo Catalog")
            assertThat(catalog.type).isEqualTo(CatalogType.SUBSCRIBED)
            assertThat(catalog.sourceUrl).isEqualTo(DEMO_CATALOG_URL)
            assertThat(catalog.installedReleaseVersion).isEqualTo("5.17.1")
        }
    }

    @Test
    fun `list catalogs returns registered catalogs`() {
        val tenant = createTenant("List Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val catalogs = ListCatalogs(tenant.id).query()
            // default + system + registered
            assertThat(catalogs).hasSize(3)
            assertThat(catalogs.map { it.name }).contains("Epistola Demo Catalog")
        }
    }

    @Test
    fun `browse catalog shows available resources of all types`() {
        val tenant = createTenant("Browse Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val result = BrowseCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).query()

            assertThat(result.resources).hasSize(12)
            assertThat(result.resources.map { it.type }).containsAll(listOf("template", "theme", "stencil", "attribute"))
            assertThat(result.resources).allMatch { it.status == ResourceStatus.AVAILABLE }
        }
    }

    @Test
    fun `install from catalog creates all resource types`() {
        val tenant = createTenant("Install Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            // Every manifest resource. A catalog is the install unit; there is no way to ask for a
            // subset, so this count is the whole manifest and nothing else (#850).
            assertThat(results).hasSize(12)
            val successful = results.filter { it.status != InstallStatus.FAILED }
            assertThat(successful).hasSize(12)

            // Verify templates were created
            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates.map { it.id.value })
                .containsExactlyInAnyOrder(
                    "hello-world",
                    "advanced-data-contract",
                    "simple-letter",
                    "demo-invoice",
                    "officiele-snelheidsbekeuring",
                    "quality-showcase",
                )

            // Verify resource type distribution
            assertThat(results.map { it.type }).containsAll(listOf("template", "theme", "stencil", "attribute", "asset"))
        }
    }

    @Test
    fun `browse after install shows installed status`() {
        val tenant = createTenant("Status Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            val result = BrowseCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).query()

            assertThat(result.resources).allMatch { it.status == ResourceStatus.INSTALLED }
        }
    }

    @Test
    fun `unregister catalog removes catalog and its resources`() {
        val tenant = createTenant("Unregister Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            UnregisterCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            val catalog = GetCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).query()
            assertThat(catalog).isNull()

            // Resources are deleted via CASCADE when catalog is removed
            val templates = ListDocumentTemplates(TenantId(tenant.id)).query()
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `reinstall after unregister creates fresh resources`() {
        val tenant = createTenant("Reinstall Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            UnregisterCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            // Re-register and install — resources were deleted by CASCADE, so this is a fresh install
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()
            val results = InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()

            assertThat(results).hasSize(12)
            assertThat(results).allMatch { it.status == InstallStatus.INSTALLED }
        }
    }

    @Test
    fun `install order respects dependencies`() {
        val tenant = createTenant("Order Test")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = DEMO_CATALOG_URL).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("epistola-demo"),
            ).execute()

            // Verify install order: attribute → theme → stencil → template
            val types = results.map { it.type }
            val attrIdx = types.indexOf("attribute")
            val themeIdx = types.indexOf("theme")
            val stencilIdx = types.indexOf("stencil")
            val templateIdx = types.indexOf("template")

            assertThat(attrIdx).isLessThan(themeIdx)
            assertThat(themeIdx).isLessThan(stencilIdx)
            assertThat(stencilIdx).isLessThan(templateIdx)
        }
    }

    /**
     * A wire-version gate failure on a *resource detail* during remote install
     * must reject the whole install (propagate the [CatalogSchemaUnknownException]),
     * not be downgraded to a single FAILED [InstallResult] by the loop's generic
     * catch. Mirrors the ZIP path in `ImportCatalogZipHandler`.
     *
     * Uses a `file:` catalog whose manifest is at the current wire version but
     * whose `codeList` detail carries a drifted `schemaVersion` — `codeList`
     * details are not fetched during dependency resolution, so the gate failure
     * first surfaces inside the install loop (the exact branch the rethrow guards).
     */
    @Test
    fun `a detail with a drifted wire version rejects the whole remote install`(@TempDir tmp: Path) {
        val tenant = createTenant("Drift Test")
        val sourceUrl = writeDriftedCatalog(tmp)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()

            assertThatThrownBy {
                InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("drift-test")).execute()
            }
                .isInstanceOf(CatalogSchemaUnknownException::class.java)
                .hasMessageContaining("every part of a catalog must carry the same wire version")
        }
    }

    /**
     * A remote (`file:`) manifest whose catalog name overflows the
     * `catalogs.name VARCHAR(255)` column must be rejected with a clear
     * [ValidationException] at registration, not surface as a raw SQLSTATE 22001
     * (an opaque 500 on the REST surface, a silent job failure on hub sync). See
     * issue #692. RegisterCatalog also backs the background `EnsureSubscribedCatalog`.
     */
    @Test
    fun `registering a catalog whose name overflows the column is rejected cleanly`(@TempDir tmp: Path) {
        val tenant = createTenant("Overlong Name Test")
        val sourceUrl = writeCatalogWithName(tmp, name = "x".repeat(256))

        withMediator {
            assertThatThrownBy {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            }.isInstanceOf(ValidationException::class.java)
        }
    }

    /**
     * A name at exactly the column ceiling (255) — legal for the column, over the
     * interactive UX cap — must still register. The backwards-compatibility
     * guarantee: content that fits the column keeps importing (issue #692).
     */
    @Test
    fun `registering a catalog whose name is exactly the column ceiling succeeds`(@TempDir tmp: Path) {
        val tenant = createTenant("At Ceiling Test")
        val name = "y".repeat(255)
        val sourceUrl = writeCatalogWithName(tmp, name = name)

        withMediator {
            val catalog = RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()
            assertThat(catalog.name).isEqualTo(name)
        }
    }

    /** Writes a minimal, resource-free current-version manifest with the given catalog [name]. */
    private fun writeCatalogWithName(dir: Path, name: String): String {
        val manifest = """
            {
              "schemaVersion": 5,
              "catalog": { "slug": "overlong-name", "name": ${escapeJson(name)} },
              "publisher": { "name": "Test" },
              "release": { "version": "1.0.0", "fingerprint": "${"0".repeat(64)}" },
              "resources": []
            }
        """.trimIndent()
        dir.resolve("catalog.json").writeText(manifest)
        return dir.resolve("catalog.json").toUri().toString()
    }

    private fun escapeJson(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Writes a minimal `file:` catalog under [dir]: a current-version (5) manifest
     * declaring one `codeList`, whose detail is deliberately stamped at version 3.
     * Returns the `file:` URL of the manifest.
     */
    /**
     * A resource that fails mid-install must leave nothing behind.
     *
     * The catalog below installs a theme cleanly and then a template the importer refuses (no
     * variants). Before the install became one transaction, the theme stayed: an install could half
     * succeed, report a failure count, and leave a catalog in a state nobody chose. The ZIP path has
     * always abandoned the whole import; this is the URL-subscribed path matching it (#850).
     */
    @Test
    fun `a resource that fails to install rolls back the whole catalog`(@TempDir tmp: Path) {
        val tenant = createTenant("Install Rollback Test")
        val sourceUrl = writeCatalogWithBrokenTemplate(tmp)

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = sourceUrl, authType = AuthType.NONE).execute()

            val results = InstallFromCatalog(
                tenantKey = tenant.id,
                catalogKey = CatalogKey.of("rollback-test"),
            ).execute()

            // The caller still learns which resource failed...
            assertThat(results).anyMatch { it.slug == "broken" && it.status == InstallStatus.FAILED }

            // ...and nothing was written, including the theme that installed before it.
            assertThat(ListThemes(TenantId(tenant.id)).query().map { it.id.value })
                .doesNotContain("rollback-theme")
            assertThat(ListDocumentTemplates(TenantId(tenant.id)).query()).isEmpty()
        }
    }

    private fun writeCatalogWithBrokenTemplate(dir: Path): String {
        val manifest = """
            {
              "schemaVersion": 6,
              "catalog": { "slug": "rollback-test", "name": "Rollback Test Catalog" },
              "publisher": { "name": "Test" },
              "release": { "version": "1.0.0", "fingerprint": "${"0".repeat(64)}" },
              "resources": [
                {
                  "type": "theme",
                  "slug": "rollback-theme",
                  "name": "Rollback Theme",
                  "detailUrl": "./resources/themes/rollback-theme.json"
                },
                {
                  "type": "template",
                  "slug": "broken",
                  "name": "Broken Template",
                  "detailUrl": "./resources/templates/broken.json"
                }
              ]
            }
        """.trimIndent()
        val theme = """
            {
              "schemaVersion": 6,
              "resource": {
                "type": "theme",
                "slug": "rollback-theme",
                "name": "Rollback Theme",
                "documentStyles": { "fontSize": "11pt" }
              }
            }
        """.trimIndent()
        // No variants: ImportTemplates requires at least one, so this fails during install rather
        // than during the wire-version or manifest gate, which is what exercises the rollback.
        val template = """
            {
              "schemaVersion": 6,
              "resource": {
                "type": "template",
                "slug": "broken",
                "name": "Broken Template",
                "templateModel": {
                  "modelVersion": 1,
                  "root": "n-root",
                  "themeRef": { "type": "inherit" },
                  "nodes": { "n-root": { "id": "n-root", "type": "root", "slots": ["s-root"] } },
                  "slots": { "s-root": { "id": "s-root", "nodeId": "n-root", "name": "children", "children": [] } }
                },
                "variants": []
              }
            }
        """.trimIndent()

        dir.resolve("catalog.json").writeText(manifest)
        dir.resolve("resources/themes").createDirectories()
        dir.resolve("resources/themes/rollback-theme.json").writeText(theme)
        dir.resolve("resources/templates").createDirectories()
        dir.resolve("resources/templates/broken.json").writeText(template)
        return dir.resolve("catalog.json").toUri().toString()
    }

    private fun writeDriftedCatalog(dir: Path): String {
        val manifest = """
            {
              "schemaVersion": 5,
              "catalog": { "slug": "drift-test", "name": "Drift Test Catalog" },
              "publisher": { "name": "Test" },
              "release": { "version": "1.0.0", "fingerprint": "${"0".repeat(64)}" },
              "resources": [
                {
                  "type": "codeList",
                  "slug": "colours",
                  "name": "Colours",
                  "detailUrl": "./resources/codelists/colours.json"
                }
              ]
            }
        """.trimIndent()
        val detail = """
            {
              "schemaVersion": 3,
              "resource": {
                "type": "codeList",
                "slug": "colours",
                "name": "Colours",
                "entries": [ { "code": "r", "label": "Red" }, { "code": "g", "label": "Green" } ]
              }
            }
        """.trimIndent()

        dir.resolve("catalog.json").writeText(manifest)
        dir.resolve("resources/codelists").createDirectories()
        dir.resolve("resources/codelists/colours.json").writeText(detail)
        return dir.resolve("catalog.json").toUri().toString()
    }
}
