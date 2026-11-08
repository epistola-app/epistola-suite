// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey

class SupportRequestAccessDeniedException(
    val tenantKey: TenantKey,
    val supportRequestId: SupportRequestKey,
) : RuntimeException("Access denied to support request $supportRequestId in tenant $tenantKey")
