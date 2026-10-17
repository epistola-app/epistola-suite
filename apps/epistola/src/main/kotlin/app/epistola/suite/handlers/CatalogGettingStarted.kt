// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

/**
 * The steps from a new catalog to a document an application such as GZAC generates from it
 * (docs/catalog-workspace-navigation.md, use case 1): add a template, publish the first version, make
 * it live in an environment, and point the application at it. Shown on the catalog page until
 * something is live, each step marked done once the catalog has reached it.
 *
 * An application asks an environment for documents and gets what is live there, so being live is
 * the end of the road; publishing alone is not.
 *
 * @property templateKey the first template in the next version, which the application asks for.
 * @property environmentKeys the tenant's environments, live or not.
 * @property liveIn the environments a version of this catalog is live in.
 */
data class CatalogGettingStarted(
    val catalogKey: String,
    val templateKey: String?,
    val published: Boolean,
    val environmentKeys: List<String>,
    val liveIn: List<String>,
    /** The viewer may add a template. */
    val mayEdit: Boolean,
    /** The viewer may publish. */
    val mayPublish: Boolean,
    /** The viewer may make a version live. */
    val mayDeploy: Boolean,
) {
    val hasTemplate: Boolean get() = templateKey != null

    val live: Boolean get() = liveIn.isNotEmpty()

    /** Whether to show the steps at all: until a version of the catalog is live somewhere. */
    val show: Boolean get() = !live

    /** The step to do now, 1 to 4; the steps before it are done. */
    val current: Int
        get() = when {
            !hasTemplate -> 1
            !published -> 2
            !live -> 3
            else -> 4
        }

    /** There is no environment to make a version live in; step 3 says to add one first. */
    val needsEnvironment: Boolean get() = environmentKeys.isEmpty()

    /** The environment the application example names: the one it is live in, or else the first. */
    val exampleEnvironment: String? get() = liveIn.firstOrNull() ?: environmentKeys.firstOrNull()
}
