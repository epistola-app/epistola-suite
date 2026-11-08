// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.installation

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Describes the installation for surfaces that report it: the support
 * connection (:modules:epistola-support) sends these when it registers, so
 * Epistola staff recognise the installation. Blank fields are left out.
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
