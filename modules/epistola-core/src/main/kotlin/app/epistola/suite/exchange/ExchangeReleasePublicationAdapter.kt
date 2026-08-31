// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.CatalogReleasePublicationRequest
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Exchange's implementation of the catalog release seam: it queues the release into the
 * publication outbox on the release transaction's own handle, binding the catalog's namespace on
 * the way if this is its first publication.
 */
@Component
class ExchangeReleasePublicationAdapter(
    private val jdbi: Jdbi,
    private val availability: ExchangeAvailability,
    private val namespaceBinder: ExchangeNamespaceBinder,
    private val store: CatalogPublicationStore,
) : CatalogReleasePublicationPort {

    override fun isPublicationAvailable(tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = availability.isAvailable(tenantKey) &&
        SecurityContext.current().hasPermission(tenantKey, Permission.CATALOG_PUBLISH) &&
        jdbi.withHandle<Boolean, Exception> { handle ->
            // Bound is not the same as still permitted: an organization can withdraw a namespace.
            // Queueing into one we no longer hold would create work that cannot move, which is the
            // thing this check exists to prevent.
            val bound = namespaceBinder.existingBinding(handle, tenantKey, catalogKey)
            bound != null && bound in namespaceBinder.grantedNamespaces(handle, tenantKey)
        }

    override fun recordReleasePublication(handle: Handle, request: CatalogReleasePublicationRequest): UUID {
        val id = UUIDv7.generate()
        store.insert(
            handle = handle,
            id = id,
            tenantKey = request.tenantKey,
            catalogKey = request.catalogKey,
            version = request.version,
            fingerprint = request.fingerprint,
            namespace = requireNotNull(namespaceBinder.existingBinding(handle, request.tenantKey, request.catalogKey)) {
                "A release reached the outbox without a namespace; isPublicationAvailable should have refused it"
            },
            archive = request.archive,
            idempotencyKey = UUIDv7.generate(),
        )
        return id
    }
}
