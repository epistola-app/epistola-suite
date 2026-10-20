// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import org.slf4j.Logger

/**
 * A request accepted before 2.0, against a template version or an environment activation, which
 * nothing can render now (docs/upgrades.md, "From 1.x to 2.0"). Expected for whatever was queued at
 * the upgrade, and resubmitted by the operator.
 */
class RequestPredatesReleasesException(message: String) : IllegalStateException(message)

/**
 * How a failed generation request is logged. A request that predates releases is expected after an
 * upgrade, often by the hundred, so it gets one line without a stack trace; anything else is an
 * error with its cause.
 */
internal object GenerationFailureLog {
    fun log(logger: Logger, requestId: Any, e: Exception) {
        if (e is RequestPredatesReleasesException) {
            logger.warn("Request {} not generated: {}", requestId, e.message)
        } else {
            logger.error("Failed to process request {}: {}", requestId, e.message, e)
        }
    }
}
