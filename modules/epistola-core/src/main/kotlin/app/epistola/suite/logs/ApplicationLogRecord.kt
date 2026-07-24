// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs

import java.time.OffsetDateTime
import java.util.UUID

/**
 * An immutable, fully-materialised application log event ready for insertion.
 *
 * Built on the **logging thread** by [ApplicationLogAppender] (so that the
 * request's tenant context and MDC are still bound), then handed to
 * [ApplicationLogIngestor] for asynchronous, batched persistence. `instance_id`
 * is stamped at drain time from `NodeIdentity`, so it is not carried here.
 */
data class ApplicationLogRecord(
    val id: UUID,
    val occurredAt: OffsetDateTime,
    val level: String,
    val logger: String,
    val message: String,
    val thread: String?,
    val tenantKey: String?,
    val traceId: String?,
    val spanId: String?,
    val exception: String?,
    val attributes: Map<String, String>?,
)
