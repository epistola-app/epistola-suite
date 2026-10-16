// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.model

import app.epistola.suite.stencils.model.StencilInstances.Finding
import app.epistola.suite.stencils.model.StencilInstances.Problem
import app.epistola.suite.validation.ValidationCode

/**
 * What [this] finding means to an author, as one clause about the instance: "fills placeholder
 * 'body', which stencil letters/letterhead does not have". The same words wherever a misfit is
 * reported, so publishing, releasing and the usage page say the same thing.
 */
fun Finding.describe(): String {
    val stencil = "$catalogKey/$stencilKey"
    val where = usedIn?.let { " (inside stencil ${it.catalogKey}/${it.stencilKey})" }.orEmpty()
    return when (problem) {
        Problem.UNKNOWN_PLACEHOLDER -> "fills placeholder '$name', which stencil $stencil does not have"
        Problem.UNBOUND_REQUIRED_PARAMETER -> "does not bind required parameter '$name' of stencil $stencil"
        Problem.UNKNOWN_PARAMETER -> "binds '$name', which stencil $stencil does not declare"
        Problem.STENCIL_NOT_FOUND -> "uses stencil $stencil, which does not exist"
        Problem.RECURSION -> "uses stencil $stencil, which contains itself"
        Problem.DEPTH_EXCEEDED -> "nests stencils too deeply at $stencil"
    } + where
}

/** The validation code a refusal over these findings carries: the first one's. */
fun List<Finding>.validationCode(): ValidationCode = when (first().problem) {
    Problem.UNBOUND_REQUIRED_PARAMETER -> ValidationCode.NODE_PARAMETER_BINDING_MISSING_REQUIRED
    Problem.RECURSION -> ValidationCode.STENCIL_RECURSION
    Problem.DEPTH_EXCEEDED -> ValidationCode.STENCIL_NESTING_DEPTH_EXCEEDED
    else -> ValidationCode.STENCIL_REFERENCE_INVALID
}
