// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.ui

/**
 * A panel another support module adds to the Epistola support page (`/platform/support`), so what an
 * installation administrator sets for that module sits with the rest of the installation's support
 * setup. The page renders [fragment] with the panel's [model] as `model`.
 */
interface SupportPlatformPanel {
    /** Where on the page, after the setup for every tenant; lowest first. */
    val order: Int get() = 0

    /** The Thymeleaf fragment, as `template :: fragment`. */
    val fragment: String

    /** Read per render, as the installation administrator viewing the page. */
    fun model(): Any
}

/** One [SupportPlatformPanel] as rendered. */
data class SupportPlatformPanelView(val fragment: String, val model: Any)
