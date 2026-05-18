package app.epistola.suite.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * TEMP diagnostic (catalog-versioning CI debug). Forces a failure whose message
 * carries the per-component source-fingerprint breakdown so a macOS-vs-Linux
 * divergence can be localized from the CI test-report artifact. Delete once the
 * bundled-fingerprint nondeterminism is resolved.
 */
class FpDiagTest {
    private val om = jsonMapper { addModule(kotlinModule()) }
    private val client = CatalogClient(RestClient.create(), om, DefaultResourceLoader())
    private val canon = CatalogCanonicalizer(om)

    @Test
    fun `DIAG demo breakdown`() {
        val audit = canon.auditFromSource(client, "classpath:epistola/catalogs/demo/catalog.json", AuthType.NONE, null)
        assertThat("\n" + audit).describedAs("FPDIAG-DEMO").isEqualTo("__force_fail__")
    }

    @Test
    fun `DIAG system breakdown`() {
        val audit = canon.auditFromSource(client, "classpath:epistola/catalogs/system/catalog.json", AuthType.NONE, null)
        assertThat("\n" + audit).describedAs("FPDIAG-SYSTEM").isEqualTo("__force_fail__")
    }
}
