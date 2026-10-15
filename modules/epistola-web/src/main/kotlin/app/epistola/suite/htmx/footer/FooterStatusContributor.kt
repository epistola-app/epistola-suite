// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx.footer

/**
 * Module-contributed status shown inside the footer bar itself, on every authenticated page —
 * tenant pages and platform pages alike, so it needs no tenant context. For installation-wide
 * status such as the support badge; per-tenant chrome uses [FooterContributor]. The fragment reads
 * its own data (for example through a `${@bean.method()}` expression) and must be cheap: it renders
 * on every page.
 */
interface FooterStatusContributor {
    fun fragments(): List<FooterFragment>
}
