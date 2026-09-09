// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * Seam through which an optional publication integration observes a catalog release.
 *
 * Publication intent must be recorded in the **same transaction** as the release, or a crash
 * between the two loses it — that is the whole point of the outbox. Sharing the open [Handle]
 * satisfies that without the catalog domain depending on the integration: catalog decides
 * *whether* to publish from its own [CatalogPublicationPolicy], and the implementation decides
 * *how* and *where*.
 *
 * Optional by design. Resolve it through `ObjectProvider`; when no implementation is present the
 * release path is exactly what it was before publication existed.
 */
interface CatalogReleasePublicationPort {
    /**
     * True when this release could actually be published: the integration is enabled for the tenant,
     * the caller is allowed to publish, and the catalog has somewhere to publish to. Consulted
     * before any archive is built, and before a release is queued — work with nowhere to go is not
     * queued at all.
     */
    fun isPublicationAvailable(tenantKey: TenantKey, catalogKey: CatalogKey): Boolean

    /** Records durable publication intent for a release being committed on [handle]. */
    fun recordReleasePublication(handle: Handle, request: CatalogReleasePublicationRequest): UUID
}

/**
 * Not a `data class`: [archive] is an array, so generated `equals`/`hashCode` would compare by
 * identity and quietly mislead.
 */
class CatalogReleasePublicationRequest(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val fingerprint: String,
    /** The exact bytes of this immutable release, retained until Exchange decides. */
    val archive: ByteArray,
)
