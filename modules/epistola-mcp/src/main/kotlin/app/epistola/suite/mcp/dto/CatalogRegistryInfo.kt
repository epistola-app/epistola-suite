// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.registry.RegistryInstallResult
import app.epistola.suite.catalog.registry.RegistryPublication
import app.epistola.suite.catalog.registry.RegistryReleases

/**
 * The releases of a catalog a container registry holds (alpha), highest first. `fingerprint` is the
 * manifest's label: shown, never trusted. `conflict` says why installing would be refused here.
 */
data class RegistryReleasesInfo(
    val registry: String,
    val repository: String,
    val namespace: String,
    val catalogKey: String,
    val releases: List<Release>,
    val conflict: String?,
) {
    data class Release(val version: String, val fingerprint: String?, val digest: String, val sizeBytes: Long, val present: Boolean)

    companion object {
        fun from(r: RegistryReleases) = RegistryReleasesInfo(
            registry = r.registry,
            repository = r.repository,
            namespace = r.namespace,
            catalogKey = r.catalogKey,
            releases = r.releases.map { Release(it.version, it.fingerprint, it.digest, it.sizeBytes, it.present) },
            conflict = r.conflict,
        )
    }
}

/** Where a release was published; `alreadyPublished` when the registry had it and nothing was pushed. */
data class RegistryPublicationInfo(
    val registry: String,
    val reference: String,
    val digest: String,
    val alreadyPublished: Boolean,
) {
    companion object {
        fun from(p: RegistryPublication) = RegistryPublicationInfo(p.registry, p.reference, p.digest, p.alreadyPublished)
    }
}

/** What installing a release from a registry did. `newlyInstalled` false when the tenant had it already. */
data class RegistryInstallInfo(
    val catalogKey: String,
    val version: String,
    val digest: String,
    val newlyInstalled: Boolean,
    val aborted: Boolean,
) {
    companion object {
        fun from(r: RegistryInstallResult) = RegistryInstallInfo(r.catalogKey.value, r.version, r.digest, r.newlyInstalled, r.aborted)
    }
}
