// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogPublicationSettingsIntegrationTest : IntegrationTestBase() {
    @Test
    fun `command stores the publication policy for an authored catalog`() {
        val tenant = createTenant("Catalog Publication")

        withMediator {
            SetCatalogPublicationSettings(
                catalog = testCatalog(tenant.id, CatalogKey.DEFAULT),
                policy = CatalogPublicationPolicy.DEFAULT_YES,
            ).execute()

            val catalog = GetCatalog(testCatalog(tenant.id, CatalogKey.DEFAULT)).query()
            assertThat(catalog?.exchangePublicationPolicy).isEqualTo(CatalogPublicationPolicy.DEFAULT_YES)
        }
    }
}
