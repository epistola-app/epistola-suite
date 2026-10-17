// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

/**
 * Where publishing makes the new version live, chosen in the publish dialog
 * (docs/catalog-workspace-navigation.md): the common case — change a template, have the application
 * use it — is then one action instead of two. Nothing is made live unless ticked, except that a tenant
 * with one environment has it ticked, so nobody makes a version live in production by accident.
 *
 * @property environments the tenant's environments, with the versions of this catalog live in each.
 * @property mayDeploy the viewer holds `ENVIRONMENT_DEPLOY`, which making a version live needs.
 * @property chosen what was ticked when the dialog is shown again after a refusal; null on first show.
 */
data class PublishLiveChoice(
    val environments: List<Target>,
    val mayDeploy: Boolean,
    val chosen: Set<String>? = null,
) {
    /** One environment to tick, with what of this catalog is live there now. */
    data class Target(val key: String, val name: String, val liveNow: List<String>)

    /** Whether to offer the choice: there is an environment, and the viewer may make versions live. */
    val offer: Boolean get() = mayDeploy && environments.isNotEmpty()

    /** The choice is withheld because the viewer may not make versions live; the dialog says so. */
    val withheld: Boolean get() = !mayDeploy && environments.isNotEmpty()

    /** Whether [key] starts ticked. */
    fun checked(key: String): Boolean = chosen?.contains(key) ?: (environments.size == 1)
}
