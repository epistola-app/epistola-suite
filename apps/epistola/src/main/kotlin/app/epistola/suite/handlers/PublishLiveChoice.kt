// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

/**
 * Where publishing makes the new version live, chosen in the publish dialog
 * (docs/catalog-workspace-navigation.md): the common case — change a template, have the application
 * use it — is then one action instead of two. Nothing is deployed unless ticked, except that a tenant
 * with one environment that is not production has it ticked; production is never ticked for you.
 * A version is deployed beside the ones an environment uses, unless [replace] is chosen.
 *
 * @property environments the tenant's environments, with the versions of this catalog live in each.
 * @property mayDeploy the viewer holds `ENVIRONMENT_DEPLOY`, which making a version live needs.
 * @property chosen what was ticked when the dialog is shown again after a refusal; null on first show.
 */
data class PublishLiveChoice(
    val environments: List<Target>,
    val mayDeploy: Boolean,
    val chosen: Set<String>? = null,
    /** Deploying stops using the versions each ticked environment uses now. */
    val replace: Boolean = false,
) {
    /** One environment to tick, with what of this catalog is live there now, and whether it is production. */
    data class Target(val key: String, val name: String, val liveNow: List<String>, val production: Boolean = false)

    /** Whether to offer the choice: there is an environment, and the viewer may make versions live. */
    val offer: Boolean get() = mayDeploy && environments.isNotEmpty()

    /** The choice is withheld because the viewer may not make versions live; the dialog says so. */
    val withheld: Boolean get() = !mayDeploy && environments.isNotEmpty()

    /** Whether [key] starts ticked. */
    fun checked(key: String): Boolean = chosen?.contains(key) ?: environments.singleOrNull()?.let { it.key == key && !it.production } ?: false

    /** Whether any environment uses a version of this catalog now, so replacing means something. */
    val anyLive: Boolean get() = environments.any { it.liveNow.isNotEmpty() }
}

/** How much a deployed version is used, as people read it on the environment and catalog pages. */
object DeploymentUsageText {
    private val WHEN = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun of(documentsRequested: Long, lastUsedAt: java.time.OffsetDateTime?): String = when {
        lastUsedAt == null || documentsRequested == 0L -> "Not used since it was deployed"
        documentsRequested == 1L -> "1 document, on ${WHEN.format(lastUsedAt)}"
        else -> "%,d documents, last on %s".format(documentsRequested, WHEN.format(lastUsedAt))
    }
}
