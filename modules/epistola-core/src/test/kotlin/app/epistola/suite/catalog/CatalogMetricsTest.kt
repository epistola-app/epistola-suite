// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CheckCatalogUpstream
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.ReleaseChannel
import app.epistola.suite.catalog.releases.ReleaseProvenance
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.toPath

/** The catalog counters the command timers cannot give: releases by how they arrived, upstream checks by outcome. */
class CatalogMetricsTest : IntegrationTestBase() {

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    private fun releases(via: ReleaseChannel): Double = meterRegistry.find(CatalogMetrics.RELEASES).tag("via", via.code).counter()?.count() ?: 0.0

    private fun checks(outcome: String): Double = meterRegistry.find(CatalogMetrics.UPSTREAM_CHECKS).tag("outcome", outcome).counter()?.count() ?: 0.0

    private fun failedChecks(): Double = meterRegistry.find(CatalogMetrics.UPSTREAM_CHECKS).counters()
        .filter { it.id.getTag("outcome") != CatalogMetrics.OUTCOME_OK }
        .sumOf { it.count() }

    private fun released(tenant: TenantKey, key: CatalogKey): CatalogId = withMediator {
        CreateCatalog(tenantKey = tenant, id = key, name = "Counted").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), key)), name = "Brand").execute()
        MarkCatalogReady(testCatalog(tenant, key)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenant, key), version = "1.0.0").execute()
        testCatalog(tenant, key)
    }

    @Test
    fun `a release cut here and one uploaded are counted by how they arrived`() {
        val key = CatalogKey.of("counted")
        val cutBefore = releases(ReleaseChannel.RELEASED)
        val source = released(createTenant("Counted Source").id, key)
        assertThat(releases(ReleaseChannel.RELEASED)).isGreaterThanOrEqualTo(cutBefore + 1)

        val uploadedBefore = releases(ReleaseChannel.UPLOAD)
        withMediator {
            val zip = ExportCatalogZip(source, "1.0.0").execute().zipBytes
            ImportCatalogZip(tenantKey = createTenant("Counted Receiver").id, zipBytes = zip, provenance = ReleaseProvenance(ReleaseChannel.UPLOAD, "counted.zip")).execute()
        }
        assertThat(releases(ReleaseChannel.UPLOAD)).isGreaterThanOrEqualTo(uploadedBefore + 1)
    }

    @Test
    fun `an upstream check is counted by what it found`(@TempDir tmp: Path) {
        val tenant = createTenant("Counted Upstream").id
        val catalog = withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = fixture(tmp), authType = AuthType.NONE).execute()
            // Its namespace is the one the source gave it, so it is named once registered.
            testCatalog(tenant, CatalogKey.of("dep-test")).also { InstallFromCatalog(catalog = it).execute() }
        }

        val okBefore = checks(CatalogMetrics.OUTCOME_OK)
        withMediator { CheckCatalogUpstream(catalog).execute() }
        assertThat(checks(CatalogMetrics.OUTCOME_OK)).isGreaterThanOrEqualTo(okBefore + 1)

        // The source is gone: the check records a failure, and is counted under it.
        tmp.toFile().listFiles()!!.forEach { it.deleteRecursively() }
        val failedBefore = failedChecks()
        withMediator { CheckCatalogUpstream(catalog).execute() }
        assertThat(failedChecks()).isGreaterThanOrEqualTo(failedBefore + 1)
    }

    /** A copy of the dependency-test fixture at [dir]: a catalog source serving release 1.0.0. */
    private fun fixture(dir: Path): String {
        val src = javaClass.classLoader.getResource("epistola/catalogs/test/dependency-test/catalog.json")!!.toURI().toPath().parent
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dir.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dir.resolve("catalog.json").toUri().toString()
    }
}
