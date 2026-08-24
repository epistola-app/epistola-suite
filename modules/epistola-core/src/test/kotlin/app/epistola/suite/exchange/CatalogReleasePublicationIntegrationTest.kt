// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleaseExchangePublication
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["epistola.exchange.enabled=true"])
class CatalogReleasePublicationIntegrationTest : IntegrationTestBase() {
    @Test
    fun `release command succeeds and queues exact archive while connection is missing`() {
        val tenant = createTenant("Queued Publication")
        val catalogKey = CatalogKey.of("public-catalog")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Public catalog").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()

            val release = ReleaseCatalogVersion(
                tenant.id,
                catalogKey,
                "1.0.0",
                exchangePublication = ReleaseExchangePublication.PUBLISH,
            ).execute()

            assertThat(release.exchangePublicationId).isNotNull()
            val publications = ListCatalogReleasePublications(tenant.id, catalogKey).query()
            assertThat(publications).hasSize(1)
            val publication = publications.single()
            assertThat(publication.version).isEqualTo("1.0.0")
            assertThat(publication.status).isEqualTo("WAITING_SETUP")
            assertThat(publication.archiveRetained).isTrue()
        }
    }

    @Test
    fun `unchanged current release can be queued later through the production command`() {
        val tenant = createTenant("Publish Current")
        val catalogKey = CatalogKey.of("publish-current")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Publish current").execute()
            SaveFeatureToggle(tenant.id, KnownFeatures.CATALOG_PUBLISHING, true).execute()
            ReleaseCatalogVersion(
                tenant.id,
                catalogKey,
                "1.0.0",
                exchangePublication = ReleaseExchangePublication.SKIP,
            ).execute()

            PublishCurrentCatalogRelease(tenant.id, catalogKey).execute()

            val publication = ListCatalogReleasePublications(tenant.id, catalogKey).query().single()
            assertThat(publication.version).isEqualTo("1.0.0")
            assertThat(publication.status).isEqualTo("WAITING_SETUP")
            assertThat(publication.archiveRetained).isTrue()
            assertThatThrownBy { PublishCurrentCatalogRelease(tenant.id, catalogKey).execute() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("already has")
        }
    }
}
