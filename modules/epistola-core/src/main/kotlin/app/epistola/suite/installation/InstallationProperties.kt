// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.installation

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Describes the installation for surfaces that report it (e.g. the
 * commercial epistola-hub integration in :modules:epistola-support).
 *
 * Defaults are blank so OSS deployments with the support module disabled
 * bind cleanly. Required-field validation is **lazy**: consumers that
 * actually need these values (the support module) check non-blankness
 * when they wire their beans.
 */
@ConfigurationProperties(prefix = "epistola.installation")
data class InstallationProperties(
    val companyName: String = "",
    val adminEmail: String = "",
    val environment: String = "",
    val name: String = "",
    val description: String? = null,
) {
    fun resolvedName(): String = name.ifBlank { "$companyName - $environment" }
}
