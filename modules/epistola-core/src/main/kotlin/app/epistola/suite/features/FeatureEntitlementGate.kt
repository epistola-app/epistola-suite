// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey

/**
 * SPI for hub-granted entitlements, implemented by the commercial support tier (an
 * `epistola.support.enabled=true` deployment). Core stays free of hub concepts: it composes a
 * feature's local toggle with this gate via [app.epistola.suite.features.queries.ResolveAvailableFeatures].
 *
 * When no implementation is present (OSS deployments, support tier off), nothing is gated and
 * feature availability is toggle-only — exactly the previous behaviour.
 */
interface FeatureEntitlementGate {
    /** Features whose availability requires a hub entitlement on top of the local toggle. */
    val gatedFeatures: Set<FeatureKey>

    /** Whether [featureKey] is currently entitled for [tenantKey] (installation-wide or tenant-scoped). */
    fun isEntitled(
        featureKey: FeatureKey,
        tenantKey: TenantKey,
    ): Boolean
}
