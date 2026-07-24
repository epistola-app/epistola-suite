// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.common.ids.StencilKey

class StencilInUseException(
    val stencilId: StencilKey,
    val templateNames: List<String>,
) : RuntimeException(
    "Cannot delete stencil '$stencilId': used by ${templateNames.joinToString(", ") { "'$it'" }}",
)
