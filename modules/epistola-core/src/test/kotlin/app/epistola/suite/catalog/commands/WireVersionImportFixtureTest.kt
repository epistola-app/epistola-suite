package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The "migrated old == native new" guarantee for the first real wire migration
 * (stencil v1 → v2, `version` becomes required). A current export is produced
 * the normal way (stencil detail stamped v2 with a `version`), then a **v1**
 * export is synthesised from it (the stencil detail re-stamped v1 with `version`
 * stripped) — exactly the shape a pre-`0.6.0` instance would have written.
 * Importing that v1 payload must:
 *
 * 1. **succeed** (the v1→v2 step assigns version 1 before binding), and
 * 2. land the **same installed state** as importing the native current export, and
 * 3. be **idempotent** on re-import (the migration is faithful enough that the
 *    stencil JSONB byte-identity check recognises the content as unchanged).
 *
 * Exercises the ZIP chokepoint end-to-end through a real DB.
 */
class WireVersionImportFixtureTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `a v1 stencil export migrates to the same installed state as a native v2 export`() {
        val tenant = createTenant("Wire Fixture")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val srcKey = CatalogKey.of("wire-src")
        val stencilKey = StencilKey.of("hdr")

        withMediator {
            // Source catalog with a single published stencil version.
            CreateCatalog(tenantKey = tenantKey, id = srcKey, name = "Wire Src").execute()
            val stencilId = StencilId(stencilKey, CatalogId(srcKey, tenantId))
            CreateStencil(id = stencilId, name = "Header", content = simpleStencil("root")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()

            val nativeZip = ExportCatalogZip(tenantKey, srcKey).execute().zipBytes

            // Native (current v2) import into its own catalog.
            val nativeKey = CatalogKey.of("wire-native")
            ImportCatalogZip(tenantKey, reSlug(nativeZip, "wire-native"), CatalogType.AUTHORED).execute()
            val nativeVersions = ListStencilVersions(StencilId(stencilKey, CatalogId(nativeKey, tenantId))).query()

            // Synthesised v1 payload of the same catalog (stencil detail re-stamped
            // v1, its `version` stripped — the pre-`0.6.0` shape).
            val legacyKey = CatalogKey.of("wire-legacy")
            val legacyZip = deriveV1Stencils(nativeZip, newSlug = "wire-legacy")
            val legacyResult = ImportCatalogZip(tenantKey, legacyZip, CatalogType.AUTHORED).execute()
            val legacyVersions = ListStencilVersions(StencilId(stencilKey, CatalogId(legacyKey, tenantId))).query()

            // 1 + 2: imported (not failed), and the same single version 1 as the
            // native import. (INSTALLED vs UPDATED only reflects whether the slug
            // already exists elsewhere in the tenant — here it does, from
            // wire-src/wire-native — which is orthogonal to the migration.)
            assertThat(legacyResult.results.single { it.type == "stencil" }.status)
                .isIn(InstallStatus.INSTALLED, InstallStatus.UPDATED)
            assertThat(legacyVersions.map { it.id.value }).isEqualTo(nativeVersions.map { it.id.value })
            assertThat(legacyVersions.single().id.value).isEqualTo(1)

            // 3: re-importing the migrated payload is a no-op.
            val reimport = ImportCatalogZip(tenantKey, legacyZip, CatalogType.AUTHORED).execute()
            assertThat(reimport.results.single { it.type == "stencil" }.status)
                .isEqualTo(InstallStatus.SKIPPED)
        }
    }

    private fun simpleStencil(rootId: String): TemplateDocument {
        val slotId = "slot-$rootId"
        return TemplateDocument(
            modelVersion = 1,
            root = rootId,
            nodes = mapOf(rootId to Node(id = rootId, type = "root", slots = listOf(slotId))),
            slots = mapOf(slotId to Slot(id = slotId, nodeId = rootId, name = "children", children = emptyList())),
            themeRef = ThemeRef.Inherit,
        )
    }

    /** Re-slugs only `catalog.json`'s `catalog.slug` so a clone imports into a fresh catalog. */
    private fun reSlug(zip: ByteArray, newSlug: String): ByteArray = rewriteZip(zip) { name, bytes ->
        if (name != "catalog.json") return@rewriteZip bytes
        val manifest = objectMapper.readTree(bytes) as ObjectNode
        (manifest.get("catalog") as ObjectNode).put("slug", newSlug)
        objectMapper.writeValueAsBytes(manifest)
    }

    /**
     * Synthesises the pre-v2 (v1) wire shape: re-slug the manifest, and on every
     * stencil detail stamp `schemaVersion` to 1 and **drop** the `version` field —
     * exactly what a pre-`0.6.0` export looked like. The migration chain must
     * restore version 1 on import.
     */
    private fun deriveV1Stencils(zip: ByteArray, newSlug: String): ByteArray = rewriteZip(zip) { name, bytes ->
        when {
            name == "catalog.json" -> {
                val manifest = objectMapper.readTree(bytes) as ObjectNode
                (manifest.get("catalog") as ObjectNode).put("slug", newSlug)
                objectMapper.writeValueAsBytes(manifest)
            }
            name.startsWith("resources/stencil/") -> {
                val detail = objectMapper.readTree(bytes) as ObjectNode
                detail.put("schemaVersion", 1)
                (detail.get("resource") as ObjectNode).remove("version")
                objectMapper.writeValueAsBytes(detail)
            }
            else -> bytes
        }
    }

    private fun rewriteZip(zip: ByteArray, transform: (name: String, bytes: ByteArray) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val transformed = transform(entry.name, zis.readBytes())
                        zos.putNextEntry(ZipEntry(entry.name))
                        zos.write(transformed)
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return out.toByteArray()
    }
}
