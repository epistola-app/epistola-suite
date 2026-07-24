// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/**
 * Controls error handling behavior during PDF rendering.
 */
enum class RenderMode {
    /** Production rendering: fail on errors (corrupt assets, missing required data). */
    STRICT,

    /** Preview/draft rendering: render a visible placeholder on errors so the user sees what's broken. */
    PREVIEW,
}
