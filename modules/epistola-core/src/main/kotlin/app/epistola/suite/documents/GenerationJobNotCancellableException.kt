// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey

class GenerationJobNotCancellableException(
    val tenantId: TenantKey,
    val requestId: GenerationRequestKey,
    val reason: String,
) : RuntimeException("Generation job ${requestId.value} cannot be cancelled for tenant ${tenantId.value}: $reason")
