// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.FontResource
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.commands.RecordInstalledRelease
import app.epistola.suite.catalog.commands.RecordedRelease
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * What [RecordInstalledRelease] records of a release describes what the release keeps.
 *
 * The system catalog's release keeps its fonts with their bundled faces, which the content built from
 * the installed rows leaves out (the wire form carries only uploaded faces). Each entry's fingerprint,
 * and a fingerprint or content hash computed while recording, has to be of the content with those
 * faces: computed before they were added, they name fonts without faces that the release does hold.
 */
class InstalledReleaseRecordTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var assembler: ReleaseContentAssembler

    @Autowired private lateinit var entries: ReleaseResourceStore

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val canonicalizer by lazy { CatalogCanonicalizer(objectMapper) }

    @Test
    fun `the system catalog's release fingerprints each font with the faces it keeps`() {
        val tenant = createTenant("Recorded system release").id
        val version = versions(tenant).single()
        val retained = retained(tenant, version)

        assertThat(retained.resourceDetails.values.mapNotNull { it.resource as? FontResource })
            .isNotEmpty
            .allSatisfy { assertThat(it.variants).`as`("${it.key}'s bundled faces").hasSize(4) }
        assertEntriesAreOfWhatIsKept(tenant, version, retained)
    }

    @Test
    fun `a system release recorded with a 1x fingerprint is identified by what it keeps, faces included`() {
        val tenant = createTenant("Recorded 1x system release").id
        // Queued with 1.x's installed release and the bare-hex fingerprint 1.x recorded, which names
        // no algorithm, so recording it fingerprints the content here.
        removeReleases(tenant)

        withMediator {
            RecordInstalledRelease(
                testCatalog(tenant, SYSTEM_CATALOG_KEY),
                installed = RecordedRelease("1.3.0", "74c0b2d145c4a970830b5ccbeb092b20abe7c8e9559f1d6b368147bac4bc09ba"),
            ).execute()
        }
        assertThat(withMediator { GetCatalog(testCatalog(tenant, SYSTEM_CATALOG_KEY)).query()!!.installedReleaseVersion })
            .`as`("the release it installed, its next version based on it").isEqualTo("1.3.0")

        val (fingerprint, contentHash) = recorded(tenant, "1.3.0")
        val retained = retained(tenant, "1.3.0")
        assertThat(contentHash).`as`("its content hash, of what it keeps").isEqualTo(canonicalizer.contentHash(retained))
        assertThat(canonicalizer.matchesFingerprint(retained, fingerprint)).`as`("its fingerprint $fingerprint, of what it keeps").isTrue()
        assertEntriesAreOfWhatIsKept(tenant, "1.3.0", retained)
    }

    @Test
    fun `a release recorded from the content in place never takes the fingerprint it was queued with`() {
        val tenant = createTenant("Recorded 1x release, claimed fingerprint").id
        removeReleases(tenant)
        // A fingerprint that names its algorithm but is not of this content: 1.x took it from the
        // publisher unchecked.
        val claimed = "v6:" + "0".repeat(64)

        withMediator { RecordInstalledRelease(testCatalog(tenant, SYSTEM_CATALOG_KEY), installed = RecordedRelease("1.3.0", claimed)).execute() }

        val (fingerprint, contentHash) = recorded(tenant, "1.3.0")
        assertThat(fingerprint).isNotEqualTo(claimed)
        assertThat(contentHash).isEqualTo(canonicalizer.contentHash(retained(tenant, "1.3.0")))
    }

    @Test
    fun `a catalog upgraded from 1x that received a release since keeps it installed`() {
        val tenant = createTenant("Recorded 1x release, received since").id
        val installed = withMediator { GetCatalog(testCatalog(tenant, SYSTEM_CATALOG_KEY)).query()!!.installedReleaseVersion }!!

        // The upgrade runner after the boot install received the bundled system catalog.
        val recordedVersion = withMediator { RecordInstalledRelease(testCatalog(tenant, SYSTEM_CATALOG_KEY), installed = RecordedRelease("1.3.0", null)).execute() }

        assertThat(recordedVersion).isEqualTo(installed)
        assertThat(withMediator { GetCatalog(testCatalog(tenant, SYSTEM_CATALOG_KEY)).query()!!.installedReleaseVersion }).isEqualTo(installed)
        val versions = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                "SELECT v.version FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id WHERE v.tenant_key = :t AND c.catalog_key = :c AND v.state = 'released'",
            ).bind("t", tenant).bind("c", SYSTEM_CATALOG_KEY).mapTo(String::class.java).list()
        }
        assertThat(versions).doesNotContain("1.3.0")
    }

    /** The system catalog as the upgrade from 1.x leaves it: 1.x's releases kept no content and are gone. */
    private fun removeReleases(tenant: TenantKey) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            DELETE FROM catalog_versions v USING catalogs c
            WHERE v.tenant_key = :t AND v.state = 'released' AND c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id AND c.catalog_key = :c
            """,
        ).bind("t", tenant).bind("c", SYSTEM_CATALOG_KEY).execute()
    }

    private fun assertEntriesAreOfWhatIsKept(tenant: TenantKey, version: String, retained: CatalogContent) {
        val kept = fingerprints.perResourceFingerprints(retained)
        val softly = SoftAssertions()
        for (entry in jdbi.withHandle<List<ReleaseResource>, Exception> { entries.entriesOf(it, testCatalog(tenant, SYSTEM_CATALOG_KEY), version) }) {
            softly.assertThat(entry.fingerprint).`as`("${entry.contentKey} is recorded with the fingerprint of what it keeps").isEqualTo(kept[entry.contentKey])
        }
        softly.assertAll()
    }

    private fun retained(tenant: TenantKey, version: String): CatalogContent = requireNotNull(assembler.assemble(testCatalog(tenant, SYSTEM_CATALOG_KEY), version)).content

    private fun versions(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { entries.versions(it, testCatalog(tenant, SYSTEM_CATALOG_KEY)) }

    private fun recorded(tenant: TenantKey, version: String): Pair<String, String> = jdbi.withHandle<Pair<String, String>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.fingerprint, r.content_hash FROM catalog_versions r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.state = 'released' AND r.tenant_key = :t AND c.catalog_key = :c AND r.version = :v
            """,
        ).bind("t", tenant).bind("c", SYSTEM_CATALOG_KEY).bind("v", version)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }
            .one()
    }
}
