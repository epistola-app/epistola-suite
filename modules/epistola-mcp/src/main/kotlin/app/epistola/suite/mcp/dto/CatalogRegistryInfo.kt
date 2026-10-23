// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.catalog.registry.ReleaseRegistryInstall
import app.epistola.suite.catalog.registry.ReleaseRegistryList
import app.epistola.suite.catalog.registry.ReleaseRegistryListing
import app.epistola.suite.catalog.registry.ReleaseRegistryPublication

/**
 * The release registries this tenant has (alpha): the configured container registries (`kind`
 * `container`) and the Epistola Exchange it is connected to (`kind` `exchange`, named `exchange`).
 */
data class ReleaseRegistriesInfo(val registries: List<Registry>) {
    data class Registry(val name: String, val kind: String, val location: String)

    companion object {
        fun from(list: ReleaseRegistryList) = ReleaseRegistriesInfo(list.registries.map { Registry(it.name, it.kind.name.lowercase(), it.location) })
    }
}

/**
 * The releases of a catalog a release registry holds (alpha), highest first. `fingerprint` is what
 * the registry says: shown, never trusted. `conflict` says why installing would be refused here.
 */
data class RegistryReleasesInfo(
    val registry: String,
    val kind: String,
    val namespace: String,
    val catalogKey: String,
    val releases: List<Release>,
    val conflict: String?,
) {
    data class Release(val version: String, val fingerprint: String?, val releaseId: String?, val sizeBytes: Long?, val present: Boolean)

    companion object {
        fun from(r: ReleaseRegistryListing) = RegistryReleasesInfo(
            registry = r.registry,
            kind = r.kind.name.lowercase(),
            namespace = r.namespace,
            catalogKey = r.catalogKey,
            releases = r.releases.map { Release(it.version, it.fingerprint, it.releaseId, it.sizeBytes, it.present) },
            conflict = r.conflict,
        )
    }
}

/**
 * What publishing a release did: `status` `published` (a container registry holds it now),
 * `already-published` (it had it; nothing was sent) or `queued` (the Exchange decides later).
 * `reference` is what a person would pull from a container registry; `digest` its manifest's.
 */
data class RegistryPublicationInfo(
    val registry: String,
    val kind: String,
    val version: String,
    val status: String,
    val digest: String?,
    val reference: String?,
) {
    companion object {
        fun from(p: ReleaseRegistryPublication) = RegistryPublicationInfo(
            registry = p.registry,
            kind = p.kind.name.lowercase(),
            version = p.version,
            status = p.status.name.lowercase().replace('_', '-'),
            digest = p.digest,
            reference = p.reference,
        )
    }
}

/** What installing a release from a release registry did. `newlyInstalled` false when the tenant had it already. */
data class RegistryInstallInfo(
    val registry: String,
    val catalogKey: String,
    val version: String,
    val newlyInstalled: Boolean,
    val aborted: Boolean,
) {
    companion object {
        fun from(r: ReleaseRegistryInstall, version: String) = RegistryInstallInfo(r.registry, r.imported.catalogKey.value, r.imported.releaseVersion ?: version, r.newlyInstalled, r.aborted)
    }
}
