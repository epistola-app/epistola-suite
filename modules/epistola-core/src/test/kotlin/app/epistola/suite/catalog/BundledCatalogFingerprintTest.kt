package app.epistola.suite.catalog

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.client.RestClient
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Drift gate for bundled catalogs (analogous to the `pg_dump --schema-only`
 * byte-identical migration check): recomputes each bundled catalog's content
 * fingerprint from its classpath content and asserts it equals the committed
 * `release.fingerprint`. Editing a demo/system resource without regenerating
 * the committed fingerprint fails the build.
 *
 * Pure unit test (no Spring/Docker) — the canonicalization + classpath fetch
 * need only a [CatalogClient] and a Kotlin-aware mapper (the same recipe the
 * app uses: `jsonMapper { addModule(kotlinModule()) }`).
 *
 * Regenerating after an intentional content change: run this test, read the
 * "actual" value from the failure message, paste it into the catalog's
 * `catalog.json` `release.fingerprint`, and bump `release.version`.
 */
class BundledCatalogFingerprintTest {

    private val objectMapper = jsonMapper { addModule(kotlinModule()) }
    private val catalogClient = CatalogClient(
        catalogRestClient = RestClient.create(),
        resourceLoader = DefaultResourceLoader(),
        schemaMigrator = CatalogSchemaMigrator(objectMapper, emptyList(), current = 4, baseline = 4),
    )
    private val canonicalizer = CatalogCanonicalizer(objectMapper)

    private fun assertBundledFingerprint(manifestUrl: String) {
        val manifest = catalogClient.fetchManifest(manifestUrl, AuthType.NONE, null)
        val committed = manifest.release.fingerprint
        val actual = canonicalizer.fingerprintFromSource(catalogClient, manifestUrl, AuthType.NONE, null)

        assertThat(actual)
            .describedAs(
                "Bundled catalog '%s' content fingerprint drifted. " +
                    "Set release.fingerprint in %s to: %s",
                manifest.catalog.slug,
                manifestUrl,
                actual,
            )
            .isEqualTo(committed)
    }

    @Test
    fun `demo catalog committed fingerprint matches its content`() {
        assertBundledFingerprint("classpath:epistola/catalogs/demo/catalog.json")
    }

    @Test
    fun `system catalog committed fingerprint matches its content`() {
        assertBundledFingerprint("classpath:epistola/catalogs/system/catalog.json")
    }
}
