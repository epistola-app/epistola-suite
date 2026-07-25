// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantKey

class TenantNotFoundException(
    val tenantId: TenantKey,
) : RuntimeException("Tenant not found: ${tenantId.value}")
