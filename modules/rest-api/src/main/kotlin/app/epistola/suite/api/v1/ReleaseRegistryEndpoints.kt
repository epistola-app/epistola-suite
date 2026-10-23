// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.model.CatalogMergeBaseRef
import app.epistola.api.model.ImportCatalogResponse
import app.epistola.api.model.RegistryReleaseDto
import app.epistola.api.model.RegistryReleaseListResponse
import app.epistola.api.model.ReleasePublicationDto
import app.epistola.api.model.ReleasePublicationStatus
import app.epistola.api.model.ReleaseRegistryDto
import app.epistola.api.model.ReleaseRegistryKind
import app.epistola.api.model.ReleaseRegistryListResponse
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.registry.InstallReleaseRegistryRelease
import app.epistola.suite.catalog.registry.ListReleaseRegistries
import app.epistola.suite.catalog.registry.ListReleaseRegistryReleases
import app.epistola.suite.catalog.registry.PublishReleaseToReleaseRegistry
import app.epistola.suite.catalog.registry.ReleaseRegistryConflictException
import app.epistola.suite.catalog.registry.ReleaseRegistryPublication
import app.epistola.suite.catalog.releases.GetCatalogNames
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.catalog.registry.ReleaseRegistryKind as DomainKind

/**
 * Release registries over REST (contract ADR 0008, alpha), for [EpistolaCatalogApi]: the Exchange
 * and the configured container registries, one set of operations. Refusals are the release
 * registry exceptions, mapped in [ApiExceptionMappings].
 */
internal class ReleaseRegistryEndpoints(private val identity: CatalogIdentityProperties) {

    fun list(tenantKey: String): ReleaseRegistryListResponse = ReleaseRegistryListResponse(
        items = ListReleaseRegistries(TenantKey.of(tenantKey)).query().registries.map { ReleaseRegistryDto(name = it.name, kind = it.kind.toDto()) },
    )

    fun releases(tenantKey: String, name: String, registry: String, namespace: String, catalogKey: String): RegistryReleaseListResponse {
        val authority = authorityOf(registry)
        val listed = ListReleaseRegistryReleases(TenantKey.of(tenantKey), name, namespace, catalogKey, authority).query()
        return RegistryReleaseListResponse(
            releaseRegistry = listed.registry,
            registry = authority,
            namespace = listed.namespace,
            catalogKey = listed.catalogKey,
            items = listed.releases.map { release ->
                // The registry's labels, never trusted: a V6 fingerprint is the release's content hash (ADR 0034).
                RegistryReleaseDto(
                    version = release.version,
                    fingerprint = release.fingerprint,
                    contentHash = release.fingerprint?.takeIf(app.epistola.suite.catalog.CatalogCanonicalizer::isContentHash),
                    releaseId = release.releaseId,
                    base = release.base?.let { CatalogMergeBaseRef(version = it.version, fingerprint = it.fingerprint, contentHash = it.fingerprint.takeIf(app.epistola.suite.catalog.CatalogCanonicalizer::isContentHash)) },
                )
            },
        )
    }

    fun install(tenantKey: String, name: String, registry: String, namespace: String, catalogKey: String, version: String): ImportCatalogResponse {
        val tenant = TenantKey.of(tenantKey)
        val installed = InstallReleaseRegistryRelease(tenant, name, namespace, catalogKey, version, authorityOf(registry)).execute()
        val result = installed.imported
        if (installed.aborted) throw IllegalStateException("Installing ${result.catalogKey.value} $version from $name failed; nothing changed")
        val names = GetCatalogNames(tenant, listOf(result.catalogKey)).query()[result.catalogKey]
        return ImportCatalogResponse(
            registry = names?.registry ?: identity.defaultRegistry,
            namespace = names?.namespace ?: namespace,
            catalogKey = result.catalogKey.value,
            catalogName = result.catalogName,
            version = result.releaseVersion ?: version,
            newlyInstalled = result.newlyInstalled,
            total = result.results.size,
            installedDependencies = result.installedDependencies,
            presentDependencies = result.presentDependencies,
        )
    }

    /** Publishes `{release}`: a released release; one naming a draft is refused (409 `conflict`). */
    fun publish(tenantKey: String, registry: String, namespace: String, catalogKey: String, release: String, to: String): ReleasePublicationDto {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val version = at.version.version?.takeUnless { at.isDraft }
            ?: throw ReleaseRegistryConflictException("'$release' is a draft of ${at.catalogKey.value}; only a released release is published. Release it first.")
        val published = PublishReleaseToReleaseRegistry(at.catalog.tenantKey, at.catalogKey, version, to).execute()
        return ReleasePublicationDto(
            status = when (published.status) {
                ReleaseRegistryPublication.Status.PUBLISHED -> ReleasePublicationStatus.PUBLISHED
                ReleaseRegistryPublication.Status.ALREADY_PUBLISHED -> ReleasePublicationStatus.ALREADY_PUBLISHED
                ReleaseRegistryPublication.Status.QUEUED -> ReleasePublicationStatus.QUEUED
            },
            releaseRegistry = published.registry,
            version = published.version,
            digest = published.digest,
        )
    }

    /** The authority a path's `{registry}` names: `~` is the default registry. */
    private fun authorityOf(registry: String): String = if (registry == "~") identity.defaultRegistry else registry

    private fun DomainKind.toDto() = when (this) {
        DomainKind.EXCHANGE -> ReleaseRegistryKind.EXCHANGE
        DomainKind.CONTAINER -> ReleaseRegistryKind.CONTAINER
    }
}
