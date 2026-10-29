// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.BundledCatalogFingerprints
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.toPath
import kotlin.io.path.writeText

/**
 * A catalog installed from its source URL is received as any archive is (contract ADR 0008,
 * "installing is importing"): what the source claims is checked, never taken on trust.
 */
class SourceInstallChecksTest : IntegrationTestBase() {

    private val depKey = CatalogKey.of("dep-test")

    /** The release fingerprint the dependency-test fixture names (BundledCatalogFingerprintTest holds it to its content). */
    private val dependencyTestFingerprint = "v6:f4bb79843c073a5ef221406b5ba665c1ff5d6d630fcc07e95bbc165e6c84c1db"

    /** A writable copy of the dependency-test fixture at [dir]; [edit] changes it first. */
    private fun fixture(dir: Path, edit: (Path) -> Unit = {}): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        edit(dir)
        return dir.resolve("catalog.json").toUri().toString()
    }

    private fun Path.replace(path: String, from: String, to: String) {
        val file = resolve(path)
        val text = file.readText()
        check(from in text) { "$path holds no '$from'" }
        file.writeText(text.replace(from, to))
    }

    private fun installRefused(tenantName: String, edit: (Path) -> Unit, tmp: Path): org.assertj.core.api.AbstractThrowableAssert<*, out Throwable> {
        val tenant = createTenant(tenantName)
        val url = fixture(tmp.resolve("source"), edit)
        return withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = url, authType = AuthType.NONE).execute()
            val refused = assertThatThrownBy { InstallFromCatalog(catalog = testCatalog(tenant.id, depKey)).execute() }
            // Nothing of it was installed: no release, no content.
            assertThat(ListCatalogReleaseSummaries(testCatalog(tenant.id, depKey)).query()).isEmpty()
            refused
        }
    }

    @Test
    fun `a binary whose bytes do not hash to the content hash its resource declares is refused`(@TempDir tmp: Path) {
        installRefused("Source Tampered Binary", { dir -> Files.write(dir.resolve("binaries/test-logo.png"), "not the logo".toByteArray()) }, tmp)
            .isInstanceOf(CatalogImportValidationException::class.java)
            .hasMessageContaining("CATALOG_ASSET_CONTENT_HASH_MISMATCH")
    }

    @Test
    fun `a manifest whose fingerprint is not its content's is refused`(@TempDir tmp: Path) {
        installRefused(
            "Source Wrong Fingerprint",
            { dir -> dir.replace("catalog.json", "\"fingerprint\": \"$dependencyTestFingerprint\"", "\"fingerprint\": \"v6:${"0".repeat(64)}\"") },
            tmp,
        )
            .isInstanceOf(CatalogImportValidationException::class.java)
            .hasMessageContaining("FINGERPRINT")
    }

    @Test
    fun `a release whose pinned dependency the tenant does not have is refused`(@TempDir tmp: Path) {
        installRefused(
            "Source Missing Pin",
            { dir ->
                dir.replace("catalog.json", "\"releaseVersion\": \"2.1.1\"", "\"releaseVersion\": \"9.9.9\"")
                // A release as its publisher cut it: its fingerprint covers the pin it names.
                BundledCatalogFingerprints.sealCatalogDirectory(dir)
            },
            tmp,
        )
            .isInstanceOf(CatalogImportDependenciesMissingException::class.java)
            .hasMessageContaining("9.9.9")
    }
}
