// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

/**
 * Optional hook to choose where a login lands when there is nowhere else to go.
 *
 * Consulted by [PopupAwareAuthenticationSuccessHandler] **only** when Spring Security has no saved
 * request — that is, the person went to the login page directly rather than being bounced there from
 * a deep link. A saved request always wins, so this can never swallow the page someone was actually
 * trying to reach.
 *
 * Returning null means "no opinion", and login lands on `/` as before.
 *
 * The one implementation today is demo mode
 * ([app.epistola.suite.demo.DemoPostLoginTarget]), which sends a visitor into their own tenant
 * rather than making them pick from a list of one.
 */
fun interface PostLoginTargetResolver {
    fun resolve(principal: EpistolaPrincipal): String?
}
