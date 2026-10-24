// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.merge.PreviewDraftMerge
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.revisions.ReleaseContentHashBackfill
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.parallelReleaseLines
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import app.epistola.catalog.canonical.CatalogCanonicalizer as PortableCatalogCanonicalizer

/**
 * A release is identified by its content hash (ADR 0034, phase 2): the V6 fingerprint of its content,
 * recorded beside the fingerprint it was cut or received with. Every "is this the same release?"
 * decision goes by it, so a release fingerprinted V5 here (as releases cut before V6 are) and the
 * same release received as a v8 archive (V6), or under another number, are one release.
 *
 * Releases recorded before V6 are played by rewriting a release's recorded fingerprint to the V5 the
 * same content had, with SQL: no command records a V5 fingerprint any more.
 */
class ReleaseIdentityTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var store: CatalogDependencyStore

    @Autowired private lateinit var renderSource: ReleaseRenderSource

    @Autowired private lateinit var backfill: ReleaseContentHashBackfill

    private val lines = CatalogKey.of("lines")

    /** The V5 fingerprint of the release [zip] carries, as a producer from before V6 recorded it. */
    private fun v5Of(zip: ByteArray): String = CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use {
        PortableCatalogCanonicalizer.qualifiedFingerprint(it, CatalogFingerprintVersion.V5).toString()
    }

    private fun fingerprintIn(zip: ByteArray): String = objectMapper.readTree(entries(zip).getValue("catalog.json")).get("release").get("fingerprint").asString()

    /** Rewrites release [version] of [catalog] in [tenant] as recorded before releases had a content hash. */
    private fun recordedBeforeV6(tenant: TenantKey, catalog: CatalogKey, version: String, fingerprint: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE catalog_releases r SET fingerprint = :f, content_hash = NULL,
                   manifest_snapshot = jsonb_set(r.manifest_snapshot, '{release,fingerprint}', to_jsonb(CAST(:f AS TEXT)))
            FROM catalogs c WHERE c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id AND r.tenant_key = :t AND c.catalog_key = :c AND r.version = :v
            """,
        ).bind("f", fingerprint).bind("t", tenant).bind("c", catalog).bind("v", version).execute()
    }

    private fun recorded(tenant: TenantKey, catalog: CatalogKey, version: String): Pair<String, String?> = jdbi.withHandle<Pair<String, String?>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.fingerprint, r.content_hash FROM catalog_releases r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.tenant_key = :t AND c.catalog_key = :c AND r.version = :v
            """,
        ).bind("t", tenant).bind("c", catalog).bind("v", version)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }
            .one()
    }

    @Test
    fun `a release cut here records its V6 fingerprint as its content hash`() {
        val a = createTenant("Identity cut").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        val (fingerprint, contentHash) = recorded(a, lines, "1.0.0")
        assertThat(fingerprint).startsWith("v6:")
        assertThat(contentHash).isEqualTo(fingerprint).isEqualTo(fingerprintIn(released.v100))
    }

    /**
     * The same release, received a second time by another route (an archive that presents the
     * catalog differently, as another registry may): the same content hash, so nothing changes. The
     * presentation is not content, so it is the same release.
     */
    @Test
    fun `a release received twice by different routes is recognised as one`() {
        val a = createTenant("Identity routes A").id
        val b = createTenant("Identity routes B").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        val presentedOtherwise = rewritten(released.v100) { manifest -> (manifest.get("catalog") as ObjectNode).put("description", "As another registry presents it") }
        assertThat(fingerprintIn(presentedOtherwise)).isEqualTo(fingerprintIn(released.v100))

        withMediator {
            assertThat(ImportCatalogZip(tenantKey = b, zipBytes = released.v100).execute().newlyInstalled).isTrue()
            assertThat(ImportCatalogZip(tenantKey = b, zipBytes = presentedOtherwise).execute().newlyInstalled)
                .`as`("the same release, by its content").isFalse()
        }
    }

    /**
     * A release this installation recorded with its V5 fingerprint, received again as the v8 archive
     * its producer exports now (V6): the same content, so the same release, and it learns its content
     * hash. Comparing the two fingerprints as strings refused it as another release under that number.
     */
    @Test
    fun `an old V5 release and its re-received v8 archive are recognised as the same release`() {
        val a = createTenant("Identity V5 A").id
        val b = createTenant("Identity V5 B").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        withMediator { ImportCatalogZip(tenantKey = b, zipBytes = released.v100).execute() }
        val v5 = v5Of(released.v100)
        recordedBeforeV6(b, lines, "1.0.0", v5)

        val again = withMediator { ImportCatalogZip(tenantKey = b, zipBytes = released.v100).execute() }

        assertThat(again.newlyInstalled).isFalse()
        assertThat(recorded(b, lines, "1.0.0")).`as`("keeps the fingerprint it was received with, and has its content hash now")
            .isEqualTo(v5 to fingerprintIn(released.v100))
    }

    /**
     * An old release exports as a v8 archive must name it: by its content hash, not the V5 it was
     * recorded with, so another installation can receive it, and it receives as the same release.
     */
    @Test
    fun `a release recorded with a V5 fingerprint exports named by its content hash`() {
        val a = createTenant("Identity export A").id
        val c = createTenant("Identity export C").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        recordedBeforeV6(a, lines, "1.0.0", v5Of(released.v100))

        val exported = withMediator { ExportCatalogZip(testCatalog(a, lines), "1.0.0").execute().zipBytes }

        assertThat(fingerprintIn(exported)).isEqualTo(fingerprintIn(released.v100)).startsWith("v6:")
        withMediator { assertThat(ImportCatalogZip(tenantKey = c, zipBytes = exported).execute().newlyInstalled).isTrue() }
        assertThat(recorded(c, lines, "1.0.0").second).isEqualTo(fingerprintIn(released.v100))
    }

    /**
     * Lineage is by hash: 1.1.0 names its base, 1.0.0, by content hash. Received here under another
     * number (0.9.0, the same content: the number is not content), that base is still found, so a
     * draft on it merges 1.1.0 against it, rather than knowing no common release.
     */
    @Test
    fun `lineage across a renumbered route still finds the merge base`() {
        val a = createTenant("Identity lineage A").id
        val b = createTenant("Identity lineage B").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        val renumbered = rewritten(released.v100) { manifest -> (manifest.get("release") as ObjectNode).put("version", "0.9.0") }
        assertThat(fingerprintIn(renumbered)).isEqualTo(fingerprintIn(released.v100))

        val preview = withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = renumbered).execute()
            // Changes of its own, so 1.1.0 is recorded beside and the next release is behind it.
            CreateTheme(ThemeId(ThemeKey.of("local"), testCatalog(TenantId(b), lines)), "Local").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = released.v110).execute()
            PreviewDraftMerge(testCatalog(b, lines), "1.1.0").query()
        }

        assertThat(preview.missingBase).isNull()
        assertThat(preview.base).`as`("1.0.0 is 0.9.0 here, by its content hash").isEqualTo("0.9.0")
    }

    /**
     * A pin taken as V5 (by a release cut before V6) on a release this installation records as V6,
     * and the other way round: the same release by content, so the pin holds and the release renders.
     */
    @Test
    fun `a pin and its release fingerprinted by different algorithms are one release`() {
        val a = createTenant("Identity pins").id
        val shared = CatalogKey.of("shared")
        val letters = CatalogKey.of("letters")
        val (sharedZip, version) = withMediator {
            CreateCatalog(tenantKey = a, id = shared, name = "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(a), shared)), "Brand").execute()
            MarkCatalogReady(testCatalog(a, shared)).execute()
            ReleaseCatalogDraft(testCatalog(a, shared), "1.0.0").execute()

            CreateCatalog(tenantKey = a, id = letters, name = "Letters").execute()
            declareDependency(a, letters, on = shared)
            val template = TemplateId(TemplateKey.of("letter"), testCatalog(TenantId(a), letters))
            CreateDocumentTemplate(template, "Letter").execute().withRequiredDataExample()
            UpdateDocumentTemplate(template, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(template.tenantKey, shared)).execute()
            MarkCatalogReady(testCatalog(a, letters)).execute()
            ExportCatalogZip(testCatalog(a, shared), "1.0.0").execute().zipBytes to ReleaseCatalogDraft(testCatalog(a, letters), "1.0.0").execute().version
        }
        val v5 = v5Of(sharedZip)
        // letters 1.0.0 pinned shared 1.0.0 as a release cut before V6 would have: by its V5.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_dependencies d SET release_fingerprint = :f FROM catalogs c
                WHERE c.tenant_key = d.tenant_key AND c.catalog_id = d.catalog_id AND d.tenant_key = :t AND c.catalog_key = 'letters' AND d.dependency_key = 'shared'
                """,
            ).bind("f", v5).bind("t", a).execute()
        }

        withMediator {
            val pin = ListReleaseDependencies(testCatalog(a, letters), version).query().single()
            assertThat(pin.releaseFingerprint).isEqualTo(v5)
            assertThat(jdbi.withHandle<Boolean, Exception> { store.pinHolds(it, a, pin) }).`as`("a V5 pin on the release recorded as V6").isTrue()
            val inputs = renderSource.resolve(GetTenant(a).query()!!, ReleaseRef(testCatalog(a, letters), version), "letter", VariantKey.INITIAL.value)
            assertThat(inputs.resolvedTheme).`as`("renders with the theme of the release it pinned").isNotNull()
        }

        // And the other way round: the release recorded as V5, the pin as V6.
        recordedBeforeV6(a, shared, "1.0.0", v5)
        val v6Pin = withMediator { ListReleaseDependencies(testCatalog(a, letters), version).query() }.single().copy(releaseFingerprint = fingerprintIn(sharedZip))
        assertThat(jdbi.withHandle<Boolean, Exception> { store.pinHolds(it, a, v6Pin) }).`as`("a V6 pin on the release recorded as V5").isTrue()
    }

    /** A release with no content hash (one recorded before V6) gets it from what it retained. */
    @Test
    fun `the backfill gives a release without a content hash the hash of what it retained`() {
        val a = createTenant("Identity backfill").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        recordedBeforeV6(a, lines, "1.1.0", v5Of(released.v110))

        assertThat(backfill.run()).isGreaterThanOrEqualTo(1)

        assertThat(recorded(a, lines, "1.1.0").second).isEqualTo(fingerprintIn(released.v110))
        assertThat(backfill.run()).`as`("nothing left").isZero()
    }

    /**
     * A release whose retained content is no longer what its fingerprint names gets no content hash:
     * the hash of what is left would make it another release here than where it was kept intact, and
     * the export's own check, which refuses to hand it on, would then pass it.
     */
    @Test
    fun `the backfill gives no content hash to a release that no longer holds what its fingerprint names`() {
        val a = createTenant("Identity altered").id
        val released = withMediator { mediator.parallelReleaseLines(a, lines) }
        recordedBeforeV6(a, lines, "1.1.0", v5Of(released.v110))
        // The release loses a resource it was cut with. No command alters what a release retained.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM release_resources e USING catalogs c
                WHERE c.tenant_key = e.tenant_key AND c.catalog_id = e.catalog_id
                  AND e.tenant_key = :t AND c.catalog_key = :c AND e.version = '1.1.0' AND e.resource_key = 'added'
                """,
            ).bind("t", a).bind("c", lines).execute()
        }

        backfill.run()

        assertThat(recorded(a, lines, "1.1.0").second).`as`("no content hash for what is no longer the release").isNull()
        assertThatThrownBy { withMediator { ExportCatalogZip(testCatalog(a, lines), "1.1.0").execute() } }
            .`as`("nor is it handed on")
            .hasMessageContaining("has been altered")
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) put(e.name, zip.readAllBytes())
                e = zip.nextEntry
            }
        }
    }

    /** [zip] with its manifest changed by [change], everything else as it was. */
    private fun rewritten(zip: ByteArray, change: (ObjectNode) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { writer ->
            for ((name, bytes) in entries(zip)) {
                writer.putNextEntry(ZipEntry(name))
                writer.write(
                    if (name == "catalog.json") {
                        objectMapper.writeValueAsBytes((objectMapper.readTree(bytes) as ObjectNode).also(change))
                    } else {
                        bytes
                    },
                )
                writer.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
