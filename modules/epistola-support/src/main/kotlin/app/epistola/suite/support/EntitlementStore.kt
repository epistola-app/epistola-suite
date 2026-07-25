// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import java.time.Instant

/** Whether a configured entitlement entry grants (ALLOW) or withholds (DENY) the feature. */
enum class EntitlementEffect {
    ALLOW,
    DENY,
}

/**
 * One entitlement entry the hub has configured for this installation. [tenant] is null for an
 * installation-wide entry; [expiresAt] is null for a perpetual one.
 */
data class StoredEntitlement(
    val featureKey: String,
    val tenant: String?,
    val effect: EntitlementEffect,
    val expiresAt: Instant?,
)

/**
 * The last-known-good entitlement set pulled from the hub, with the [revision] it corresponds to
 * (used to decide when a hub-signalled change warrants a refresh) and the time it was fetched.
 * [revision] defaults to 0 so a set stored before revisions existed still deserializes.
 */
data class StoredEntitlements(
    val entries: List<StoredEntitlement>,
    val revision: Long = 0,
    val fetchedAt: Instant,
)

/**
 * Persists the hub-delivered entitlement set via the suite-wide [AppMetadataService] under
 * [METADATA_KEY], mirroring [AppMetadataInstallationStore]. Last-known-good: only a successful hub
 * fetch overwrites it, so a transient hub outage never downgrades entitlements.
 */
class EntitlementStore(
    private val metadata: AppMetadataService,
) {
    fun load(): StoredEntitlements? = metadata.getAs<StoredEntitlements>(METADATA_KEY)

    fun save(value: StoredEntitlements) {
        metadata.setAs(METADATA_KEY, value)
    }

    companion object {
        const val METADATA_KEY = "support.entitlements"
    }
}
