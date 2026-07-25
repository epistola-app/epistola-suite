// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey

class FeedbackAccessDeniedException(
    val tenantKey: TenantKey,
    val feedbackId: FeedbackKey,
) : RuntimeException("Access denied to feedback $feedbackId in tenant $tenantKey")
