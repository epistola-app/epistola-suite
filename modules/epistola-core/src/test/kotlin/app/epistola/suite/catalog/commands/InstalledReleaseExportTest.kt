// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.zip.ZipInputStream

/**
 * A release this installation installed from elsewhere exports as its publisher released it: with
 * the publisher's fingerprint, which dependents pin, so another installation can install it. A
 * hand-written archive leaves defaults out and names its publisher with a URL; the release keeps
 * the archive's own content rather than a rebuild from the rows its install wrote.
 */
class InstalledReleaseExportTest : IntegrationTestBase() {

    private fun manifest(zip: ByteArray): String = ZipInputStream(zip.inputStream()).use { stream ->
        generateSequence { stream.nextEntry }.first { it.name == "catalog.json" }
        String(stream.readAllBytes())
    }

    @Test
    fun `a catalog installed from its source exports with the fingerprint it was published with`() {
        val tenant = createTenant("installed-export-url").id
        val key = CatalogKey.of("epistola-demo")
        withMediator {
            RegisterCatalog(tenant, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant, catalogKey = key).execute()
        }
        val catalog = withMediator { GetCatalog(tenant, key).query()!! }

        val export = withMediator { ExportCatalogZip(tenant, key).execute() }

        assertThat(manifest(export.zipBytes)).contains("\"fingerprint\":\"${catalog.installedFingerprint}\"")
    }

    @Test
    fun `an archive of it installs in another tenant and exports again with the same fingerprint`() {
        val source = createTenant("installed-export-source").id
        val key = CatalogKey.of("epistola-demo")
        val zip = withMediator {
            RegisterCatalog(source, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = source, catalogKey = key).execute()
            ExportCatalogZip(source, key).execute().zipBytes
        }
        val target = createTenant("installed-export-target").id

        val again = withMediator {
            ImportCatalogZip(target, zip).execute()
            ExportCatalogZip(target, key).execute().zipBytes
        }

        val published = Regex("\"release\":\\{[^}]*\\}").find(manifest(zip))!!.value.replace(Regex("\"releasedAt\":\"[^\"]*\","), "")
        assertThat(Regex("\"release\":\\{[^}]*\\}").find(manifest(again))!!.value.replace(Regex("\"releasedAt\":\"[^\"]*\","), ""))
            .`as`("the same release and fingerprint; only when it was installed differs").isEqualTo(published)
    }
}
