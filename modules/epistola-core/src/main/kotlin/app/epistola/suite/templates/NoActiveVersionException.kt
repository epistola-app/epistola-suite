// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey

class NoActiveVersionException(
    val tenantId: TenantKey,
    val variantId: VariantKey,
    val environmentId: EnvironmentKey,
) : RuntimeException("No active version found for variant $variantId in environment $environmentId for tenant $tenantId")
