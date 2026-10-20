// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An installed demo catalog exports: it declares the system catalog its fonts and attributes come
 * from (contract ADR 0003), and an export refuses a catalog with an undeclared reference.
 */
class DemoCatalogExportIntegrationTest : IntegrationTestBase() {

    @Test
    fun `an installed demo catalog exports`() {
        val tenant = createTenant("Demo export").id
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant, sourceUrl = "classpath:epistola/catalogs/demo/catalog.json").execute() }

        val export = withMediator { ExportCatalogZip(tenantKey = tenant, catalogKey = CatalogKey.of("epistola-demo")).execute() }

        assertThat(export.zipBytes).isNotEmpty()
    }
}
