// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TenantKey

data class FeatureToggle(
    val tenantKey: TenantKey,
    val featureKey: FeatureKey,
    val enabled: Boolean,
)
