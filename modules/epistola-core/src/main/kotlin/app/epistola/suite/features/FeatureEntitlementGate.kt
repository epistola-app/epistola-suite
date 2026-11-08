// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey

/**
 * SPI that can gate a feature's availability on an entitlement on top of its local toggle, composed
 * via [app.epistola.suite.features.queries.ResolveAvailableFeatures].
 *
 * Nothing implements it: no feature is gated on the support connection. Its OAuth scopes decide
 * only what is *sent* to Epistola, never what is usable locally (ADR 0050 §5,
 * `docs/feature-toggles.md`), so do not implement this to tie a feature to a scope. Without an
 * implementation feature availability is toggle-only.
 */
interface FeatureEntitlementGate {
    /** Features whose availability requires an entitlement on top of the local toggle. */
    val gatedFeatures: Set<FeatureKey>

    /** Whether [featureKey] is currently entitled for [tenantKey] (installation-wide or tenant-scoped). */
    fun isEntitled(
        featureKey: FeatureKey,
        tenantKey: TenantKey,
    ): Boolean
}
