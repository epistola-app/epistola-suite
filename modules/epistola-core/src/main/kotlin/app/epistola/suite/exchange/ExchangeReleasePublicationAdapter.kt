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
            // The catalog's namespace must be one the connection grants now: an organization can
            // withdraw a namespace, and queueing into one we no longer hold would create work that
            // cannot move, which is the thing this check exists to prevent.
            val namespace = namespaceBinder.namespaceOf(handle, tenantKey, catalogKey)
            namespace != null && namespace in namespaceBinder.grantedNamespaces(handle, tenantKey)
        }

    /**
     * Reads the outbox on the caller's handle, so the answer is inside the transaction that is about
     * to delete the release — asking on a separate connection would leave a window in which a
     * publication could start between the check and the delete.
     *
     * [CatalogPublicationStatus.isActive] is the one definition of "being sent" and stays here.
     * `findByVersion` takes `FOR UPDATE`, so the row a sender would claim is locked by the deleting
     * transaction rather than merely observed — there is one publication per version, by unique key.
     */
    override fun isSendInFlight(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): Boolean = store.findByVersion(handle, tenantKey, catalogKey, version)?.status?.isActive == true

    override fun isNamespaceFixed(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): Boolean = namespaceBinder.isLocked(handle, tenantKey, catalogKey)

    override fun publishedNamespace(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey): String? = namespaceBinder.publishedNamespace(handle, tenantKey, catalogKey)

    override fun preferredNamespace(handle: Handle, tenantKey: TenantKey): String? = namespaceBinder.tenantDefault(handle, tenantKey)

    override fun namespaceChanged(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, namespace: String) = store.repointUnsubmitted(handle, tenantKey, catalogKey, namespace)

    override fun recordReleasePublication(handle: Handle, request: CatalogReleasePublicationRequest): UUID {
        val id = UUIDv7.generate()
        store.insert(
            handle = handle,
            id = id,
            tenantKey = request.tenantKey,
            catalogKey = request.catalogKey,
            version = request.version,
            fingerprint = request.fingerprint,
            namespace = requireNotNull(namespaceBinder.namespaceOf(handle, request.tenantKey, request.catalogKey)) {
                "A release reached the outbox without a namespace; isPublicationAvailable should have refused it"
            },
            archive = request.archive,
            idempotencyKey = UUIDv7.generate(),
        )
        return id
    }
}
