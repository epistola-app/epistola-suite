package app.epistola.suite.catalog.probe

import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.testing.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

class SystemReleaseProbeTest : IntegrationTestBase() {
    @Autowired lateinit var assembler: ReleaseContentAssembler

    @Autowired lateinit var fp: CatalogFingerprintService

    @Autowired lateinit var om: ObjectMapper

    @Autowired lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @Test
    fun `probe the bundled system release exports as released`() {
        val tenant = createTenant("probe-system").id
        val retained = assembler.assemble(tenant, CatalogKey.of("system"), "2.0.0")!!
        val rebuilt = fp.perResourceFingerprints(retained.content)
        val recorded = jdbi.withHandle<Map<String, String>, Exception> { h ->
            h.createQuery("SELECT resource_type || '/' || resource_key AS k, fingerprint FROM release_entries WHERE tenant_key = :t AND version = '2.0.0'")
                .bind("t", tenant).map { rs, _ -> rs.getString("k") to rs.getString("fingerprint") }.list().toMap()
        }
        System.out.println("PROBE keys rebuilt=${rebuilt.keys.size} recorded=${recorded.keys.size}")
        (rebuilt.keys + recorded.keys).sorted().forEach { k -> if (rebuilt[k] != recorded[k]) System.out.println("PROBE diff $k rebuilt=${rebuilt[k]} recorded=${recorded[k]}") }
        for (k in listOf("attribute/country", "codeList/iso-639-1", "theme/default", "image/01966a00-0000-7000-8000-000000000010")) {
            System.out.println("PROBE json $k " + om.writeValueAsString(retained.content.resourceDetails[k]))
        }
        System.out.println("PROBE catalog=" + om.writeValueAsString(retained.content.catalog))
        System.out.println("PROBE deps=" + om.writeValueAsString(retained.content.dependencies) + " cdeps=" + om.writeValueAsString(retained.content.catalogDependencies))
        System.out.println("PROBE release=" + om.writeValueAsString(retained.release))
    }
}
