package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.ResourceDetail
import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.StencilNodeKeys
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifies the wire-format `version` field on stencils round-trips correctly
 * across export/import, that idempotent re-imports are no-ops, and that
 * conflicts either fail (default) or trigger the renumber-and-rewrite path.
 */
class StencilVersionImportConflictTest : IntegrationTestBase() {

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `round-trip preserves stencil version numbers`() {
        val tenant = createTenant("Round-trip Version")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val sourceKey = CatalogKey.of("rt-source-${tenantKey.value.take(8)}")
        val targetKey = CatalogKey.of("rt-target-${tenantKey.value.take(8)}")
        val sourceCat = CatalogId(sourceKey, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = sourceKey, name = "RT Source").execute()
            val stencilKey = StencilKey.of("rt-stencil")
            val stencilId = StencilId(stencilKey, sourceCat)
            CreateStencil(id = stencilId, name = "RT Stencil", content = simpleStencil("v1")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), stencilId)).execute()
            CreateStencilVersion(stencilId = stencilId, content = simpleStencil("v2")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), stencilId)).execute()
            CreateStencilVersion(stencilId = stencilId, content = simpleStencil("v3")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(3), stencilId)).execute()

            val zip = ExportCatalogZip(tenantKey, sourceKey).execute().zipBytes

            // Manifest carries the latest published version (3)
            val manifest = readManifest(zip)
            val stencilEntry = manifest.resources.single { it.type == "stencil" }
            val detail = readResourceDetail(zip, stencilEntry.detailUrl) as StencilResource
            assertThat(detail.version).isEqualTo(3)

            // Import into a fresh catalog — version 3 is preserved
            ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = renameInManifest(zip, targetKey.value),
                catalogType = CatalogType.AUTHORED,
            ).execute()

            val installedVersions = ListStencilVersions(
                stencilId = StencilId(stencilKey, CatalogId(targetKey, tenantId)),
            ).query()
            assertThat(installedVersions).hasSize(1)
            assertThat(installedVersions.single().id.value).isEqualTo(3)
        }
    }

    @Test
    fun `re-import with identical content at same version is idempotent`() {
        val tenant = createTenant("Idempotent")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val key = CatalogKey.of("idem-${tenantKey.value.take(8)}")
        val cat = CatalogId(key, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = key, name = "Idem").execute()
            val stencilKey = StencilKey.of("idem-stencil")
            CreateStencil(id = StencilId(stencilKey, cat), name = "Idem", content = simpleStencil("c1")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), StencilId(stencilKey, cat))).execute()

            val zip = ExportCatalogZip(tenantKey, key).execute().zipBytes

            ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = zip,
                catalogType = CatalogType.AUTHORED,
            ).execute()

            // Still exactly one version after re-import
            val versions = ListStencilVersions(stencilId = StencilId(stencilKey, cat)).query()
            assertThat(versions).hasSize(1)
            assertThat(versions.single().id.value).isEqualTo(1)
        }
    }

    @Test
    fun `import fails on stencil version conflict with structured report`() {
        val tenant = createTenant("Conflict Fail")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val key = CatalogKey.of("cf-${tenantKey.value.take(8)}")
        val cat = CatalogId(key, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = key, name = "Conflict Fail").execute()
            val stencilKey = StencilKey.of("conflict-stencil")
            CreateStencil(id = StencilId(stencilKey, cat), name = "C", content = simpleStencil("local")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), StencilId(stencilKey, cat))).execute()

            // Build a ZIP carrying v1 with *different* content
            val zip = buildManualZip(
                catalogSlug = key.value,
                stencil = StencilResource(
                    slug = stencilKey.value,
                    name = "C",
                    version = 1,
                    content = simpleStencil("remote"),
                ),
            )

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zip,
                    catalogType = CatalogType.AUTHORED,
                    onStencilConflict = OnStencilConflict.FAIL,
                ).execute()
            }
                .isInstanceOf(StencilVersionImportConflictsException::class.java)
                .satisfies({ ex ->
                    val e = ex as StencilVersionImportConflictsException
                    val conflict = e.conflicts.single()
                    assertThat(conflict.stencilKey).isEqualTo(stencilKey)
                    assertThat(conflict.version).isEqualTo(1)
                })

            // Target's v1 content unchanged
            val versions = ListStencilVersions(stencilId = StencilId(stencilKey, cat)).query()
            assertThat(versions).hasSize(1)
        }
    }

    @Test
    fun `a mis-versioned resource detail aborts the whole import (not a single FAILED resource)`() {
        val tenant = createTenant("Schema Gate")
        val tenantKey = tenant.id
        val key = CatalogKey.of("sg-${tenantKey.value.take(8)}")

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = key, name = "Schema Gate").execute()

            // The stencil detail matches the manifest's wire version, but the
            // template detail carries a different one — a catalog is one bundle at
            // one wire version, so this is malformed. The version gate (in the
            // install loop) must reject the WHOLE import — surfacing the dedicated
            // CatalogSchemaException — rather than downgrading the template to a
            // single FAILED resource swallowed by the generic catch.
            val zip = buildManualZip(
                catalogSlug = key.value,
                stencil = StencilResource(slug = "sg-stencil", name = "S", version = 1, content = simpleStencil("local")),
                template = simpleStencil("tpl"),
                templateDetailSchemaVersion = 99,
            )

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zip,
                    catalogType = CatalogType.AUTHORED,
                    onStencilConflict = OnStencilConflict.FAIL,
                ).execute()
            }.isInstanceOf(CatalogSchemaException::class.java)
        }
    }

    @Test
    fun `renumber override installs at next version and rewrites template pins`() {
        // Build a proper source catalog (stencil + template pinning that stencil),
        // export → ZIP, then import into a *different* target slug that already
        // has the same stencil at v1 with different content. RENUMBER mode
        // installs the conflicting v1 at v2 and rewrites the imported
        // template's pin from v1 → v2.
        val tenant = createTenant("Renumber")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val sourceKey = CatalogKey.of("rn-source")
        val targetKey = CatalogKey.of("rn-target")
        val sourceCat = CatalogId(sourceKey, tenantId)
        val targetCat = CatalogId(targetKey, tenantId)

        withMediator {
            // Source: stencil at v1 with "remote-v1" content + template pinning v1
            CreateCatalog(tenantKey = tenantKey, id = sourceKey, name = "RN Source").execute()
            val stencilKey = StencilKey.of("rn-stencil")
            CreateStencil(id = StencilId(stencilKey, sourceCat), name = "RN", content = simpleStencil("remote-v1")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), StencilId(stencilKey, sourceCat))).execute()
            publishTemplateReferencingStencil(sourceCat, "rn-template", stencilKey, stencilVersion = 1)
            val zip = ExportCatalogZip(tenantKey, sourceKey).execute().zipBytes

            // Target: pre-existing v1 with DIFFERENT content (the conflict)
            CreateCatalog(tenantKey = tenantKey, id = targetKey, name = "RN Target").execute()
            CreateStencil(id = StencilId(stencilKey, targetCat), name = "RN", content = simpleStencil("local-v1")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), StencilId(stencilKey, targetCat))).execute()

            // Retarget the export to land in the target catalog
            val retargeted = renameInManifest(zip, targetKey.value)

            ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = retargeted,
                catalogType = CatalogType.AUTHORED,
                authoredMode = AuthoredImportMode.MERGE,
                onStencilConflict = OnStencilConflict.RENUMBER,
            ).execute()

            // Target has both versions; local v1 untouched, source's v1 landed at v2
            val versions = ListStencilVersions(stencilId = StencilId(stencilKey, targetCat)).query()
                .sortedBy { it.id.value }
            assertThat(versions).hasSize(2)
            assertThat(versions.map { it.id.value }).containsExactly(1, 2)

            // Imported template's stencil pin is rewritten from v1 → v2
            val templateModelJson = jdbi.withHandle<String, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT template_model::text
                    FROM template_versions
                    WHERE tenant_key = :t AND catalog_key = :c AND template_key = :tpl
                    ORDER BY id DESC LIMIT 1
                    """,
                )
                    .bind("t", tenantKey)
                    .bind("c", targetKey)
                    .bind("tpl", TemplateKey.of("rn-template"))
                    .mapTo(String::class.java)
                    .one()
            }
            // JSONB serialization spacing varies; match the key/value semantically
            assertThat(templateModelJson).contains("\"version\": 2")
            assertThat(templateModelJson).doesNotContain("\"version\": 1")
        }
    }

    @Test
    fun `renumber leaves cross-catalog stencil pins alone`() {
        // Same RENUMBER scenario as above, but the imported template carries
        // TWO stencil refs to the same slug — one own-catalog (no explicit
        // `catalogKey` prop) and one cross-catalog (`catalogKey` pointing at a
        // different catalog). RENUMBER must rewrite ONLY the own-catalog pin;
        // the cross-catalog ref belongs to another catalog's versioning and
        // must be left untouched. Built with a manual ZIP so we control the
        // template node shape directly (no round-trip export).
        val tenant = createTenant("CrossCat Renumber")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val key = CatalogKey.of("cc-${tenantKey.value.take(8)}")
        val cat = CatalogId(key, tenantId)

        withMediator {
            // Target already has the own-catalog stencil at v1 with content X
            CreateCatalog(tenantKey = tenantKey, id = key, name = "CC Target").execute()
            val stencilKey = StencilKey.of("cc-stencil")
            CreateStencil(id = StencilId(stencilKey, cat), name = "CC", content = simpleStencil("local-v1")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), StencilId(stencilKey, cat))).execute()

            // ZIP carries stencil v1 with DIFFERENT content (RENUMBER target)
            // and a template with two stencil refs: an own-catalog one (will
            // be rewritten) and a cross-catalog one (must be left alone).
            val zip = buildManualZip(
                catalogSlug = key.value,
                stencil = StencilResource(
                    slug = stencilKey.value,
                    name = "CC",
                    version = 1,
                    content = simpleStencil("remote-v1"),
                ),
                template = templateWithOwnAndCrossStencilRefs(stencilKey),
            )

            ImportCatalogZip(
                tenantKey = tenantKey,
                zipBytes = zip,
                catalogType = CatalogType.AUTHORED,
                authoredMode = AuthoredImportMode.MERGE,
                onStencilConflict = OnStencilConflict.RENUMBER,
            ).execute()

            // Source's v1 landed at v2; local v1 untouched
            val versions = ListStencilVersions(stencilId = StencilId(stencilKey, cat)).query()
                .sortedBy { it.id.value }
            assertThat(versions.map { it.id.value }).containsExactly(1, 2)

            val templateModelJson = jdbi.withHandle<String, Exception> { handle ->
                handle.createQuery(
                    """
                    SELECT template_model::text
                    FROM template_versions
                    WHERE tenant_key = :t AND catalog_key = :c AND template_key = :tpl
                    ORDER BY id DESC LIMIT 1
                    """,
                )
                    .bind("t", tenantKey)
                    .bind("c", key)
                    .bind("tpl", TemplateKey.of("manual-template"))
                    .mapTo(String::class.java)
                    .one()
            }

            val doc = objectMapper.readValue(templateModelJson, TemplateDocument::class.java)
            val ownPin = (doc.nodes["own-ref"]?.props?.get(StencilNodeKeys.PROP_VERSION) as? Number)?.toInt()
            val crossPin = (doc.nodes["cross-ref"]?.props?.get(StencilNodeKeys.PROP_VERSION) as? Number)?.toInt()
            assertThat(ownPin)
                .describedAs("own-catalog stencil pin rewritten by RENUMBER")
                .isEqualTo(2)
            assertThat(crossPin)
                .describedAs("cross-catalog stencil pin must be left alone")
                .isEqualTo(1)
        }
    }

    @Test
    fun `renumber rejected for AUTHORED REPLACE`() {
        val tenant = createTenant("Reject Replace")
        val tenantKey = tenant.id
        val tenantId = TenantId(tenantKey)
        val key = CatalogKey.of("rr-${tenantKey.value.take(8)}")
        val cat = CatalogId(key, tenantId)

        withMediator {
            CreateCatalog(tenantKey = tenantKey, id = key, name = "RR").execute()
            val stencilKey = StencilKey.of("rr-stencil")
            CreateStencil(id = StencilId(stencilKey, cat), name = "RR", content = simpleStencil("local")).execute()
            PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), StencilId(stencilKey, cat))).execute()

            val zip = buildManualZip(
                catalogSlug = key.value,
                stencil = StencilResource(
                    slug = stencilKey.value,
                    name = "RR",
                    version = 1,
                    content = simpleStencil("remote"),
                ),
            )

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zip,
                    catalogType = CatalogType.AUTHORED,
                    authoredMode = AuthoredImportMode.REPLACE,
                    onStencilConflict = OnStencilConflict.RENUMBER,
                ).execute()
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("RENUMBER is only supported for AUTHORED MERGE")
        }
    }

    @Test
    fun `import fails explicitly when stencil version is missing on the wire`() {
        val tenant = createTenant("Missing Version")
        val tenantKey = tenant.id
        val key = CatalogKey.of("mv-legacy")

        withMediator {
            // Hand-crafted ZIP where the stencil JSON omits `version` —
            // simulates a pre-0.6.0 export. Jackson's StencilResource constructor
            // has no default for `version` so deserialization throws. The
            // stencil-conflict pre-scan parses every stencil detail up front,
            // so this surfaces as a hard import failure (the user's chosen
            // policy: "missing version, please re-export").
            val zip = buildManualZipWithRawStencilJson(
                catalogSlug = key.value,
                stencilJson = """
                {
                  "schemaVersion": 2,
                  "resource": {
                    "type": "stencil",
                    "slug": "no-version",
                    "name": "Legacy",
                    "content": ${objectMapper.writeValueAsString(simpleStencil("legacy"))}
                  }
                }
                """.trimIndent(),
            )

            assertThatThrownBy {
                ImportCatalogZip(
                    tenantKey = tenantKey,
                    zipBytes = zip,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }
                .hasMessageContaining("version")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private fun publishTemplateReferencingStencil(catalogId: CatalogId, templateSlug: String, stencilKey: StencilKey, stencilVersion: Int) {
        val templateKey = TemplateKey.of(templateSlug)
        val templateId = TemplateId(templateKey, catalogId)
        CreateDocumentTemplate(id = templateId, name = templateSlug).execute()

        val variantId = VariantId(VariantKey.of("${templateKey.value}-default"), templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = templateReferencingStencil(templateKey, stencilKey, stencilVersion),
        ).execute()
        PublishVersion(versionId = VersionId(VersionKey.of(1), variantId)).execute()
    }

    private fun templateReferencingStencil(templateKey: TemplateKey, stencilKey: StencilKey, stencilVersion: Int): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "s" to Node(
                id = "s",
                type = "stencil",
                slots = listOf("s-children"),
                props = mapOf("stencilId" to stencilKey.value, "version" to stencilVersion),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("s")),
            "s-children" to Slot(id = "s-children", nodeId = "s", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /**
     * Template with two stencil nodes referencing the same slug. The first
     * (`own-ref`) has no explicit `catalogKey` prop — it resolves against the
     * importing catalog. The second (`cross-ref`) carries an explicit
     * `catalogKey` pointing at a different catalog and must therefore be
     * skipped by the RENUMBER pin rewrite.
     */
    private fun templateWithOwnAndCrossStencilRefs(stencilKey: StencilKey): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "own-ref" to Node(
                id = "own-ref",
                type = "stencil",
                slots = listOf("own-children"),
                props = mapOf(
                    StencilNodeKeys.PROP_STENCIL_ID to stencilKey.value,
                    StencilNodeKeys.PROP_VERSION to 1,
                ),
            ),
            "cross-ref" to Node(
                id = "cross-ref",
                type = "stencil",
                slots = listOf("cross-children"),
                props = mapOf(
                    StencilNodeKeys.PROP_STENCIL_ID to stencilKey.value,
                    StencilNodeKeys.PROP_VERSION to 1,
                    StencilNodeKeys.PROP_CATALOG_KEY to "some-other-catalog",
                ),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("own-ref", "cross-ref")),
            "own-children" to Slot(id = "own-children", nodeId = "own-ref", name = "children", children = emptyList()),
            "cross-children" to Slot(id = "cross-children", nodeId = "cross-ref", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun readManifest(zip: ByteArray): CatalogManifest = readZipEntry(zip, "catalog.json").let {
        objectMapper.readValue(it, CatalogManifest::class.java)
    }

    private fun readResourceDetail(zip: ByteArray, detailUrl: String) = objectMapper.readValue(readZipEntry(zip, detailUrl.removePrefix("./")), ResourceDetail::class.java).resource

    private fun readZipEntry(zip: ByteArray, name: String): ByteArray {
        java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == name) return zis.readAllBytes()
                entry = zis.nextEntry
            }
        }
        error("entry not found: $name")
    }

    /**
     * Re-bundle the ZIP under a different catalog slug. Reads every entry,
     * rewrites only the `catalog.slug` in `catalog.json`, writes everything
     * back. Used to import into a fresh slug without re-exporting from scratch.
     */
    private fun renameInManifest(zip: ByteArray, newSlug: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val bytes = zis.readAllBytes()
                    val out = if (entry.name == "catalog.json") {
                        val m = objectMapper.readValue(bytes, CatalogManifest::class.java)
                        objectMapper.writeValueAsBytes(m.copy(catalog = m.catalog.copy(slug = newSlug)))
                    } else {
                        bytes
                    }
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(out)
                    zos.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return baos.toByteArray()
    }

    private fun buildManualZip(
        catalogSlug: String,
        stencil: StencilResource,
        template: TemplateDocument? = null,
        /** Wire version stamped on the *template* detail — used to exercise the version gate. */
        templateDetailSchemaVersion: Int = 4,
    ): ByteArray {
        val resources = mutableListOf<app.epistola.catalog.protocol.ResourceEntry>()
        resources.add(
            app.epistola.catalog.protocol.ResourceEntry(
                type = "stencil",
                slug = stencil.slug,
                name = stencil.name,
                description = stencil.description,
                detailUrl = "./resources/stencil/${stencil.slug}.json",
            ),
        )
        val templateKey = template?.let { TemplateKey.of("manual-template") }
        if (template != null) {
            resources.add(
                app.epistola.catalog.protocol.ResourceEntry(
                    type = "template",
                    slug = templateKey!!.value,
                    name = templateKey.value,
                    description = null,
                    detailUrl = "./resources/template/${templateKey.value}.json",
                ),
            )
        }

        val manifest = CatalogManifest(
            schemaVersion = 4,
            catalog = app.epistola.catalog.protocol.CatalogInfo(catalogSlug, "Manual", null),
            publisher = app.epistola.catalog.protocol.PublisherInfo("Test"),
            release = app.epistola.catalog.protocol.ReleaseInfo("0.0.0-dev", null, null),
            resources = resources,
            dependencies = null,
        )

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("catalog.json"))
            zos.write(objectMapper.writeValueAsBytes(manifest))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("resources/stencil/${stencil.slug}.json"))
            zos.write(objectMapper.writeValueAsBytes(ResourceDetail(schemaVersion = 4, resource = stencil)))
            zos.closeEntry()
            if (template != null) {
                val variantKey = "${templateKey!!.value}-default"
                val tpl = app.epistola.catalog.protocol.TemplateResource(
                    slug = templateKey.value,
                    name = templateKey.value,
                    templateModel = template,
                    variants = listOf(
                        app.epistola.catalog.protocol.VariantEntry(
                            id = variantKey,
                            title = null,
                            attributes = null,
                            templateModel = null,
                            isDefault = true,
                        ),
                    ),
                )
                zos.putNextEntry(ZipEntry("resources/template/${templateKey.value}.json"))
                zos.write(objectMapper.writeValueAsBytes(ResourceDetail(schemaVersion = templateDetailSchemaVersion, resource = tpl)))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun buildManualZipWithRawStencilJson(catalogSlug: String, stencilJson: String): ByteArray {
        val resources = listOf(
            app.epistola.catalog.protocol.ResourceEntry(
                type = "stencil",
                slug = "no-version",
                name = "Legacy",
                description = null,
                detailUrl = "./resources/stencil/no-version.json",
            ),
        )
        val manifest = CatalogManifest(
            schemaVersion = 4,
            catalog = app.epistola.catalog.protocol.CatalogInfo(catalogSlug, "Legacy", null),
            publisher = app.epistola.catalog.protocol.PublisherInfo("Test"),
            release = app.epistola.catalog.protocol.ReleaseInfo("0.0.0-dev", null, null),
            resources = resources,
            dependencies = null,
        )
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("catalog.json"))
            zos.write(objectMapper.writeValueAsBytes(manifest))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("resources/stencil/no-version.json"))
            zos.write(stencilJson.toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
