// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * A stencil's own content can bind resources in another catalog, exactly as a template's can.
 * Export used to scan only template models, so those dependencies were silently dropped from the
 * manifest and a re-import had nothing telling it the other catalog was required.
 *
 * A font binding is used rather than a nested stencil reference only because Suite still applies
 * its capability gate on authoring a stencil definition whose content contains a stencil node
 * (see docs/stencils.md). Nested stencil *instances* in a template are supported, and
 * epistola-catalog already specifies the nested-definition composition portably, so the export
 * scan covers stencil references too and stays correct once that gate lifts.
 */
class StencilCrossCatalogDependencyTest : IntegrationTestBase() {
    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `export emits a cross-catalog dependency for a font bound inside stencil content`() {
        val tenant = createTenant("Stencil deps")
        val tenantId = TenantId(tenant.id)
        val source = CatalogKey.of("letters")
        val header = StencilId(StencilKey.of("header"), CatalogId(source, tenantId))

        val zip = withMediator {
            CreateCatalog(tenant.id, source, "Letters").execute()
            CreateStencil(header, "Header").execute()
            UpdateStencilDraft(StencilVersionId(VersionKey.of(1), header), bindsSystemFont()).execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()

            ExportCatalogZip(tenantKey = tenant.id, catalogKey = source).execute()
        }

        val dependencies = manifestOf(zip.zipBytes).dependencies.orEmpty()
        assertThat(dependencies).contains(DependencyRef.Font(catalogKey = SYSTEM_CATALOG_KEY.value, slug = "inter"))
    }

    @Test
    fun `same-catalog references travel relative so an export installs under a different key`() {
        val tenant = createTenant("Rehome")
        val tenantId = TenantId(tenant.id)
        val source = CatalogKey.of("rehome-source")
        val header = StencilId(StencilKey.of("header"), CatalogId(source, tenantId))

        val zip = withMediator {
            CreateCatalog(tenant.id, source, "Source").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(source, tenantId)), "Brand").execute()
            CreateStencil(header, "Header").execute()
            UpdateStencilDraft(StencilVersionId(VersionKey.of(1), header), usesOwnCatalogTheme()).execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            ExportCatalogZip(tenantKey = tenant.id, catalogKey = source).execute()
        }

        // Storage is absolute so relocation is safe; the wire form must stay relative, otherwise
        // the source catalog's key is baked in and the ZIP can only be installed under that name.
        val stencilJson = entriesOf(zip.zipBytes).getValue("resources/stencil/header.json")
        assertThat(stencilJson).doesNotContain(source.value)

        // No cross-catalog dependency is declared for a reference the catalog satisfies itself.
        assertThat(manifestOf(zip.zipBytes).dependencies.orEmpty())
            .noneSatisfy { assertThat(it).isInstanceOf(DependencyRef.Theme::class.java) }
    }

    private fun usesOwnCatalogTheme(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRefOverride(themeId = "brand"),
    )

    private fun bindsSystemFont(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("children")),
            "title" to Node(
                id = "title",
                type = "text",
                styles = mapOf("fontFamily" to mapOf("slug" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value)),
            ),
        ),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf("title"))),
        themeRef = ThemeRef.Inherit,
    )

    private fun entriesOf(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) put(entry.name, String(zip.readAllBytes(), StandardCharsets.UTF_8))
                entry = zip.nextEntry
            }
        }
    }

    private fun manifestOf(bytes: ByteArray): CatalogManifest {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "catalog.json") {
                    return objectMapper.readValue(String(zip.readAllBytes(), StandardCharsets.UTF_8), CatalogManifest::class.java)
                }
                entry = zip.nextEntry
            }
        }
        error("catalog.json missing from export")
    }
}
