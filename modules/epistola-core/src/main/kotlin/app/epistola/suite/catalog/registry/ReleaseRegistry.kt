// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.catalog.protocol.ReleaseBase
import app.epistola.suite.catalog.commands.ImportCatalogZipResult
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.springframework.stereotype.Component

/**
 * A release registry (contract ADR 0008, alpha): a place releases are published to and installed
 * from. Two kinds: the container registries this installation is configured with
 * ([ContainerReleaseRegistries]), and the Epistola Exchange a tenant is connected to, which the
 * `exchange` package contributes through [ReleaseRegistryProvider] (`catalog` never references
 * `exchange`).
 *
 * A release registry is named by the installation and is configuration, never part of a catalog's
 * name: a catalog in it is addressed by namespace and key only.
 */
interface ReleaseRegistry {
    val summary: ReleaseRegistrySummary

    val name: String get() = summary.name

    /** The releases of [namespace]/[catalogKey] it holds, highest first: what it says of them, a label. */
    fun list(tenantKey: TenantKey, namespace: String, catalogKey: String, authority: String? = null): ReleaseRegistryListing

    /** Installs release [version] of [namespace]/[catalogKey], exactly as importing its archive does. */
    fun install(tenantKey: TenantKey, namespace: String, catalogKey: String, version: String, authority: String? = null): ReleaseRegistryInstall

    /** Publishes released release [version] of [catalogKey], exactly as it was released. */
    fun publish(catalog: CatalogId, version: String): ReleaseRegistryPublication
}

/** Contributes the release registries of one kind that a tenant has. */
interface ReleaseRegistryProvider {
    fun registries(tenantKey: TenantKey): List<ReleaseRegistry>
}

enum class ReleaseRegistryKind {
    /** An Epistola Exchange: grants namespaces, and publishes asynchronously (its publication outbox). */
    EXCHANGE,

    /** A container registry (OCI distribution): carries releases, grants no names, publishes while asked. */
    CONTAINER,
}

/** The name the Epistola Exchange has as a release registry; no container registry may take it. */
const val EXCHANGE_RELEASE_REGISTRY_NAME = "exchange"

/** A release registry as people see it: what to pick ([name]), of which [kind], and where it is ([location]). */
data class ReleaseRegistrySummary(val name: String, val kind: ReleaseRegistryKind, val location: String)

/**
 * The releases of one catalog a release registry holds, highest first.
 *
 * @property conflict why installing would be refused before anything is fetched (the catalog key is
 *   held here by another catalog, or one that receives its releases elsewhere), or null.
 */
data class ReleaseRegistryListing(
    val registry: String,
    val kind: ReleaseRegistryKind,
    val namespace: String,
    val catalogKey: String,
    val releases: List<ReleaseRegistryRelease>,
    val conflict: String?,
) {
    /** Whether there is a release here the tenant can install now. */
    val installable: Boolean get() = conflict == null && releases.any { !it.present }
}

/**
 * One release in a release registry, as the registry says: [fingerprint], and [releaseId] and
 * [base] when it records them. [present] when this tenant has that release of the catalog already.
 */
data class ReleaseRegistryRelease(
    val version: String,
    val fingerprint: String?,
    val releaseId: String? = null,
    val base: ReleaseBase? = null,
    val sizeBytes: Long? = null,
    val present: Boolean = false,
)

/** What installing a release from a release registry did: the import's own result. */
data class ReleaseRegistryInstall(
    val registry: String,
    val imported: ImportCatalogZipResult,
    val aborted: Boolean,
) {
    val newlyInstalled: Boolean get() = !aborted && imported.newlyInstalled
}

/**
 * What publishing a release did: [status] says it, as the kinds publish differently. [digest] is a
 * container registry's manifest digest; [reference] what a person would pull from it, when there is
 * such a thing.
 */
data class ReleaseRegistryPublication(
    val registry: String,
    val kind: ReleaseRegistryKind,
    val version: String,
    val status: Status,
    val digest: String? = null,
    val reference: String? = null,
) {
    enum class Status {
        /** The registry holds it now. */
        PUBLISHED,

        /** The registry held it already, with this fingerprint; nothing was sent. */
        ALREADY_PUBLISHED,

        /** The registry's outbox has it (the Exchange decides later), also when it was queued already. */
        QUEUED,
    }
}

/** Every release registry a tenant has: the providers' registries, containers first. */
@Component
class ReleaseRegistryDirectory(private val providers: List<ReleaseRegistryProvider>) {
    fun all(tenantKey: TenantKey): List<ReleaseRegistry> = providers.flatMap { it.registries(tenantKey) }

    /** The registry called [name]. @throws ReleaseRegistryNotFoundException when the tenant has none. */
    fun named(tenantKey: TenantKey, name: String): ReleaseRegistry {
        val all = all(tenantKey)
        return all.firstOrNull { it.name == name.trim() } ?: throw ReleaseRegistryNotFoundException(name.trim(), all.map { it.name })
    }
}

/** A refusal of a release registry operation, in words a person can act on. */
sealed class ReleaseRegistryException(message: String) : RuntimeException(message)

/** This installation has no release registry called [name] (contract `release-registry-not-found`, 404). */
class ReleaseRegistryNotFoundException(val name: String, val known: List<String>) :
    ReleaseRegistryException(
        if (known.isEmpty()) {
            "There is no release registry called '$name': this installation has none."
        } else {
            "There is no release registry called '$name'. There are: ${known.joinToString()}."
        },
    )

/** The registry holds this version with other content (contract `release-number-taken`, 409). */
class ReleaseNumberTakenException(message: String) : ReleaseRegistryException(message)

/** The registry (the Exchange) does not grant this installation the catalog's namespace (contract `namespace-not-granted`, 409). */
class NamespaceNotGrantedException(message: String) : ReleaseRegistryException(message)

/** The registry does not hold the release asked for (404 `not-found`). */
class RegistryReleaseNotFoundException(message: String) : ReleaseRegistryException(message)

/** What the registry served is not the catalog release asked for (400 `bad-request`). */
class RegistryArchiveRejectedException(message: String) : ReleaseRegistryException(message)

/** The operation conflicts with what the tenant has (409 `conflict`): a key held by another catalog, a draft, publishing switched off. */
class ReleaseRegistryConflictException(message: String) : ReleaseRegistryException(message)
