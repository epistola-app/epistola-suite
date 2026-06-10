package app.epistola.suite.feedback

import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey

class FeedbackAccessDeniedException(
    val tenantKey: TenantKey,
    val feedbackId: FeedbackKey,
) : RuntimeException("Access denied to feedback $feedbackId in tenant $tenantKey")
