// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.system

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.commands.ImportArchiveReader
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.LegacyCatalogName
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.sources.ManifestUrlReleaseSource
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Every tenant installs the bundled system catalog through the archive path (contract ADR 0008): its
 * 14 MB of fonts are assembled into an archive, read back, verified against their hashes and hashed
 * again by the font store. Profiled on 2 November, the hashing was 80 % of installing the system
 * catalog, and creating tenants most of the integration tests' time. The classpath cannot change
 * while the JVM runs, so a bundled release is assembled once and read once; the font store's hash
 * cache, which remembers arrays by identity, then answers for every tenant after the first.
 */
class BundledReleaseArchiveReuseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var client: CatalogClient

    @Autowired
    private lateinit var archiveBuilder: CatalogArchiveBuilder

    @Autowired
    private lateinit var reader: ImportArchiveReader

    @Test
    fun `two tenants receive the system catalog as one archive`() {
        // Built here rather than injected, so it is the source production runs.
        val source = ManifestUrlReleaseSource(client, archiveBuilder, app.epistola.suite.catalog.CatalogSizeLimits())
        val first = systemCatalogOf(createTenant("Bundled A"))
        val second = systemCatalogOf(createTenant("Bundled B"))

        val firstArchive = source.archive(first, first.latestReleaseVersion!!)
        val secondArchive = source.archive(second, second.latestReleaseVersion!!)

        assertThat(secondArchive).isSameAs(firstArchive)
    }

    @Test
    fun `a bundled archive is read once, so its binaries are the same arrays for every tenant`() {
        val source = ManifestUrlReleaseSource(client, archiveBuilder, app.epistola.suite.catalog.CatalogSizeLimits())
        val first = systemCatalogOf(createTenant("Bundled C"))
        val second = systemCatalogOf(createTenant("Bundled D"))
        val archive = source.archive(first, first.latestReleaseVersion!!)

        val firstRead = reader.read(importOf(source, first, archive))
        val secondRead = reader.read(importOf(source, second, archive))

        val largest = firstRead.entries.maxBy { it.value.size }
        assertThat(largest.value.size).isGreaterThan(100_000)
        assertThat(secondRead.entries[largest.key]).isSameAs(largest.value)
    }

    @Test
    fun `an archive that is not a bundled release is read anew every time`() {
        val source = ManifestUrlReleaseSource(client, archiveBuilder, app.epistola.suite.catalog.CatalogSizeLimits())
        val catalog = systemCatalogOf(createTenant("Bundled E"))
        // A copy: the same content, as an uploaded archive arrives, in an array of its own.
        val uploaded = source.archive(catalog, catalog.latestReleaseVersion!!).copyOf()

        val firstRead = reader.read(importOf(source, catalog, uploaded))
        val secondRead = reader.read(importOf(source, catalog, uploaded))

        val largest = firstRead.entries.maxBy { it.value.size }
        assertThat(secondRead.entries[largest.key]).isNotSameAs(largest.value).isEqualTo(largest.value)
    }

    private fun systemCatalogOf(tenant: Tenant): Catalog = withMediator {
        checkNotNull(GetCatalog(testCatalog(TenantId(tenant.id), SYSTEM_CATALOG_KEY)).query())
    }

    private fun importOf(source: ManifestUrlReleaseSource, catalog: Catalog, archive: ByteArray) = ImportCatalogZip(
        catalog.tenantKey,
        archive,
        confirmMigration = true,
        legacyName = LegacyCatalogName(catalog.registry, catalog.namespace),
        provenance = source.provenance(catalog),
    )
}
