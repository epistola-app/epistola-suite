// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey

class DraftNotFoundException(
    val tenantId: TenantKey,
    val variantId: VariantKey,
) : RuntimeException("No draft exists for variant $variantId in tenant $tenantId")
