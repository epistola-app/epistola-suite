// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogPublishOutcome
import app.epistola.suite.catalog.commands.CatalogPublishOutcome.Kind
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.Environment
import app.epistola.suite.handlers.CatalogPublishSeveralHandler.DeployOffer
import app.epistola.suite.handlers.CatalogPublishSeveralHandler.DeployOffer.Withheld
import app.epistola.suite.handlers.CatalogPublishSeveralHandler.OutcomeView
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** Publish several's offer to deploy what it published: when it is made, and why it is not. */
class PublishSeveralDeployOfferTest {

    private val tenant = TenantKey.of("acme")

    private fun outcome(catalog: String, kind: Kind, version: String? = null) = OutcomeView(catalog.replaceFirstChar { it.uppercase() }, CatalogPublishOutcome(testCatalog(tenant, catalog), kind, version = version))

    private val created = OffsetDateTime.parse("2026-10-31T09:00:00Z")

    private val acceptance = Environment(EnvironmentKey.of("acceptance"), tenant, "Acceptance", createdAt = created, updatedAt = created)

    private val outcomes = listOf(
        outcome("shared", Kind.PUBLISHED, "1.0.0"),
        outcome("forms", Kind.REFUSED),
        outcome("letters", Kind.PUBLISHED, "2.1.0"),
        outcome("notes", Kind.UNCHANGED),
    )

    @Test
    fun `it offers what was published, in the order it was published, and nothing else`() {
        val offer = DeployOffer.of(outcomes, mayDeploy = true, environments = listOf(acceptance))

        assertThat(offer.offered).isTrue()
        assertThat(offer.releases).containsExactly(
            "${testCatalog(tenant, "shared").address}@1.0.0",
            "${testCatalog(tenant, "letters").address}@2.1.0",
        )
    }

    @Test
    fun `it is withheld for want of something published, a permission or an environment, and says which`() {
        val nothing = listOf(outcome("forms", Kind.REFUSED), outcome("notes", Kind.UNCHANGED))

        assertThat(DeployOffer.of(nothing, mayDeploy = true, environments = listOf(acceptance)).withheld).isEqualTo(Withheld.NOTHING_PUBLISHED)
        assertThat(DeployOffer.of(outcomes, mayDeploy = false, environments = listOf(acceptance)).withheld).isEqualTo(Withheld.NOT_PERMITTED)
        assertThat(DeployOffer.of(outcomes, mayDeploy = true, environments = emptyList()).withheld).isEqualTo(Withheld.NO_ENVIRONMENT)
    }
}
