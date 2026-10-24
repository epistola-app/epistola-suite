// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.CatalogReleasePublicationRequest
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.CatalogId
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

    override fun isPublicationAvailable(catalog: CatalogId): Boolean = availability.isAvailable(catalog.tenantKey) &&
        SecurityContext.current().hasPermission(catalog.tenantKey, Permission.CATALOG_PUBLISH) &&
        jdbi.withHandle<Boolean, Exception> { handle ->
            // The catalog's namespace must be one the connection grants now: an organization can
            // withdraw a namespace, and queueing into one we no longer hold would create work that
            // cannot move, which is the thing this check exists to prevent.
            // A catalog named under local never leaves the organisation (ADR 0034, section 7), even
            // when its namespace token is one the connection grants.
            val namespace = namespaceBinder.namespaceOf(handle, catalog)
            namespace != null && !namespaceBinder.isLocal(handle, catalog) && namespace in namespaceBinder.grantedNamespaces(handle, catalog.tenantKey)
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
    override fun isSendInFlight(handle: Handle, catalog: CatalogId, version: String): Boolean = store.findByVersion(handle, catalog, version)?.status?.isActive == true

    override fun isNamespaceFixed(handle: Handle, catalog: CatalogId): Boolean = namespaceBinder.isLocked(handle, catalog)

    override fun publishedNamespace(handle: Handle, tenantKey: app.epistola.suite.common.ids.TenantKey, catalogKey: app.epistola.suite.common.ids.CatalogKey): String? = namespaceBinder.publishedNamespace(handle, tenantKey, catalogKey)

    override fun preferredNamespace(handle: Handle, tenantKey: TenantKey): String? = namespaceBinder.tenantDefault(handle, tenantKey)

    override fun namespaceChanged(handle: Handle, catalog: CatalogId, namespace: String) = store.repointUnsubmitted(handle, catalog, namespace)

    override fun recordReleasePublication(handle: Handle, request: CatalogReleasePublicationRequest): UUID {
        val id = UUIDv7.generate()
        store.insert(
            handle = handle,
            id = id,
            catalog = request.catalog,
            version = request.version,
            fingerprint = request.fingerprint,
            namespace = requireNotNull(namespaceBinder.namespaceOf(handle, request.catalog)) {
                "A release reached the outbox without a namespace; isPublicationAvailable should have refused it"
            },
            archive = request.archive,
            idempotencyKey = UUIDv7.generate(),
        )
        return id
    }
}
