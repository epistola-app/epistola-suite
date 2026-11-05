// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.common.ids.TenantKey
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #1008: whether a release is offered to Exchange again follows its publication's status the way
 * PublishCurrentCatalogRelease decides it, rather than "any publication row means sent".
 */
class CatalogPublicationStateOffersPublishingTest {

    @Test
    fun `a version is offered unless it is queued, accepted or rejected`() {
        val offered = CatalogPublicationStatus.entries.associateWith { status -> state(publication("1.0.0", status)).offersPublishing("1.0.0") }

        assertEquals(
            mapOf(
                CatalogPublicationStatus.READY to false,
                CatalogPublicationStatus.SUBMITTED to false,
                CatalogPublicationStatus.RETRY to false,
                CatalogPublicationStatus.ACCEPTED to false,
                CatalogPublicationStatus.REJECTED to false,
                CatalogPublicationStatus.FAILED to true,
                CatalogPublicationStatus.CANCELLED to true,
            ),
            offered,
        )
    }

    @Test
    fun `a version never queued is offered, and another version's publication does not count`() {
        val state = state(publication("1.0.0", CatalogPublicationStatus.ACCEPTED))

        assertEquals(true, state.offersPublishing("1.1.0"))
        assertEquals(null, state.publicationOf("1.1.0"))
    }

    @Test
    fun `nothing is offered without the right to publish or somewhere to send it`() {
        assertEquals(false, state(canPublish = false).offersPublishing("1.0.0"))
        assertEquals(false, state(connected = false).offersPublishing("1.0.0"))
    }

    private fun state(
        vararg publications: CatalogReleasePublication,
        canPublish: Boolean = true,
        connected: Boolean = true,
    ) = CatalogPublicationState(
        available = true,
        policy = CatalogPublicationPolicy.INHERIT,
        allowsReleaseOverride = true,
        defaultPublish = false,
        namespace = "public-services",
        publications = publications.toList(),
        canPublishCurrentRelease = false,
        isRetry = false,
        namespaceLocked = true,
        availableNamespaces = listOf("public-services"),
        canPublish = canPublish,
        connected = connected,
        exchangeOrganizationUrl = null,
        suggestedNamespace = "public-services",
        exchangeAuthorityUrl = null,
    )

    private fun publication(version: String, status: CatalogPublicationStatus) = CatalogReleasePublication(
        id = UUID.randomUUID(),
        tenantKey = TenantKey.of("tenant"),
        catalogKey = CatalogKey.of("letters"),
        version = version,
        namespace = "public-services",
        status = status,
        idempotencyKey = UUID.randomUUID(),
        remotePublicationId = null,
        attempts = 1,
        archiveRetained = false,
        submittedFor = null,
        inFlightFor = Duration.ZERO,
        failure = null,
        createdAt = OffsetDateTime.parse("2026-11-05T10:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-11-05T10:00:00Z"),
    )
}
