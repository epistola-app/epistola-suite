// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.installation

import java.time.Instant
import java.util.UUID

/**
 * Stable identity of this Epistola Suite installation, persisted under the
 * `installation` key in `app_metadata`. The same id is shared across every
 * pod in a multi-pod deployment.
 */
data class Installation(
    val id: UUID,
    val createdAt: Instant,
)
