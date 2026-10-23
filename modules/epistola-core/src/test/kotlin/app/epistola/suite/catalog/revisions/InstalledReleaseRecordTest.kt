// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.catalog.protocol.FontResource
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.commands.RecordInstalledRelease
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
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

    @Autowired private lateinit var entries: ReleaseEntryStore

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
        // The system catalog as 1.x left it: installed at 1.x's release, with the bare-hex fingerprint
        // 1.x recorded, which names no algorithm, so recording it fingerprints the content here. No
        // command writes a 1.x fingerprint.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE catalogs SET installed_release_version = '1.3.0', installed_fingerprint = :f WHERE tenant_key = :t AND catalog_key = :c",
            ).bind("f", "74c0b2d145c4a970830b5ccbeb092b20abe7c8e9559f1d6b368147bac4bc09ba").bind("t", tenant).bind("c", SYSTEM_CATALOG_KEY).execute()
        }

        withMediator { RecordInstalledRelease(tenant, SYSTEM_CATALOG_KEY).execute() }

        val (fingerprint, contentHash) = recorded(tenant, "1.3.0")
        val retained = retained(tenant, "1.3.0")
        assertThat(contentHash).`as`("its content hash, of what it keeps").isEqualTo(canonicalizer.contentHash(retained))
        assertThat(canonicalizer.matchesFingerprint(retained, fingerprint)).`as`("its fingerprint $fingerprint, of what it keeps").isTrue()
        assertEntriesAreOfWhatIsKept(tenant, "1.3.0", retained)
    }

    private fun assertEntriesAreOfWhatIsKept(tenant: TenantKey, version: String, retained: CatalogContent) {
        val kept = fingerprints.perResourceFingerprints(retained)
        val softly = SoftAssertions()
        for (entry in jdbi.withHandle<List<ReleaseEntry>, Exception> { entries.entriesOf(it, tenant, SYSTEM_CATALOG_KEY, version) }) {
            softly.assertThat(entry.fingerprint).`as`("${entry.contentKey} is recorded with the fingerprint of what it keeps").isEqualTo(kept[entry.contentKey])
        }
        softly.assertAll()
    }

    private fun retained(tenant: TenantKey, version: String): CatalogContent = requireNotNull(assembler.assemble(tenant, SYSTEM_CATALOG_KEY, version)).content

    private fun versions(tenant: TenantKey): List<String> = jdbi.withHandle<List<String>, Exception> { entries.versions(it, tenant, SYSTEM_CATALOG_KEY) }

    private fun recorded(tenant: TenantKey, version: String): Pair<String, String> = jdbi.withHandle<Pair<String, String>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.fingerprint, r.content_hash FROM catalog_releases r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.tenant_key = :t AND c.catalog_key = :c AND r.version = :v
            """,
        ).bind("t", tenant).bind("c", SYSTEM_CATALOG_KEY).bind("v", version)
            .map { rs, _ -> rs.getString(1) to rs.getString(2) }
            .one()
    }
}
