// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.CatalogReleasePublicationRequest
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Exchange's implementation of the catalog release seam: it queues the release into the
 * publication outbox on the release transaction's own handle, binding the catalog's namespace on
 * the way if this is its first publication.
 */
@Component
class ExchangeReleasePublicationAdapter(
    private val availability: ExchangeAvailability,
    private val namespaceBinder: ExchangeNamespaceBinder,
    private val store: CatalogPublicationStore,
) : CatalogReleasePublicationPort {

    override fun isPublicationAvailable(tenantKey: TenantKey): Boolean = availability.isAvailable(tenantKey)

    override fun recordReleasePublication(handle: Handle, request: CatalogReleasePublicationRequest): UUID {
        val id = UUIDv7.generate()
        store.insert(
            handle = handle,
            id = id,
            tenantKey = request.tenantKey,
            catalogKey = request.catalogKey,
            version = request.version,
            fingerprint = request.fingerprint,
            namespace = namespaceBinder.resolveAndBind(handle, request.tenantKey, request.catalogKey),
            archive = request.archive,
            idempotencyKey = UUIDv7.generate(),
        )
        return id
    }
}
