// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.common.ids.StencilKey

/** Deleting [stencilId] is refused: [usedBy] names the templates and stencils that use it. */
class StencilInUseException(
    val stencilId: StencilKey,
    val usedBy: List<String>,
) : RuntimeException(
    "Cannot delete stencil '$stencilId': used by ${usedBy.joinToString(", ") { "'$it'" }}",
)
