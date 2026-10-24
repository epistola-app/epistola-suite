// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The configured container registries as release registries (kind `container`): the same for every
 * tenant, each dispatching the container commands ([ListRegistryReleases], [InstallReleaseFromRegistry],
 * [PublishReleaseToRegistry]), whose refusals it names as release registry refusals.
 */
@Component
@Order(0)
class ContainerReleaseRegistries(
    private val registries: CatalogRegistries,
) : ReleaseRegistryProvider {
    override fun registries(tenantKey: TenantKey): List<ReleaseRegistry> = registries.all().map { ContainerReleaseRegistry(ReleaseRegistrySummary(it.name, ReleaseRegistryKind.CONTAINER, it.location)) }
}

private class ContainerReleaseRegistry(override val summary: ReleaseRegistrySummary) : ReleaseRegistry {

    override fun list(tenantKey: TenantKey, namespace: String, catalogKey: String, authority: String?): ReleaseRegistryListing = refusals {
        val listed = ListRegistryReleases(tenantKey, name, namespace, catalogKey, authority).query()
        ReleaseRegistryListing(
            registry = name,
            kind = ReleaseRegistryKind.CONTAINER,
            namespace = listed.namespace,
            catalogKey = listed.catalogKey,
            releases = listed.releases.map { ReleaseRegistryRelease(version = it.version, fingerprint = it.fingerprint, sizeBytes = it.sizeBytes, present = it.present) },
            conflict = listed.conflict,
        )
    }

    override fun install(tenantKey: TenantKey, namespace: String, catalogKey: String, version: String, authority: String?): ReleaseRegistryInstall = refusals {
        val installed = InstallReleaseFromRegistry(tenantKey, name, namespace, catalogKey, version, authority).execute()
        ReleaseRegistryInstall(name, installed.imported, installed.aborted)
    }

    override fun publish(catalog: CatalogId, version: String): ReleaseRegistryPublication = refusals {
        val published = PublishReleaseToRegistry(catalog, version, name).execute()
        ReleaseRegistryPublication(
            registry = name,
            kind = ReleaseRegistryKind.CONTAINER,
            version = published.version,
            status = if (published.alreadyPublished) ReleaseRegistryPublication.Status.ALREADY_PUBLISHED else ReleaseRegistryPublication.Status.PUBLISHED,
            digest = published.digest,
            reference = published.reference,
        )
    }

    /** The container commands' refusals, as release registry refusals; any other passes as it is. */
    private fun <T> refusals(block: () -> T): T = try {
        block()
    } catch (e: ValidationException) {
        throw when (e.code) {
            ValidationCode.REGISTRY_UNKNOWN -> ReleaseRegistryNotFoundException(name, listOf(name))
            ValidationCode.REGISTRY_TAG_TAKEN -> ReleaseNumberTakenException(e.message)
            ValidationCode.REGISTRY_RELEASE_UNAVAILABLE -> RegistryReleaseNotFoundException(e.message)
            ValidationCode.REGISTRY_ARCHIVE_REJECTED -> RegistryArchiveRejectedException(e.message)
            ValidationCode.REGISTRY_CATALOG_KEY_TAKEN -> ReleaseRegistryConflictException(e.message)
            else -> e
        }
    }
}
