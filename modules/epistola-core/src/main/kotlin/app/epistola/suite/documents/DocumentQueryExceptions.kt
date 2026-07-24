// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TenantKey

class GenerationJobNotFoundException(
    val tenantId: TenantKey,
    val requestId: GenerationRequestKey,
) : RuntimeException("Generation job ${requestId.value} not found for tenant ${tenantId.value}")

class DocumentNotFoundException(
    val tenantId: TenantKey,
    val documentId: DocumentKey,
) : RuntimeException("Document ${documentId.value} not found for tenant ${tenantId.value}")
