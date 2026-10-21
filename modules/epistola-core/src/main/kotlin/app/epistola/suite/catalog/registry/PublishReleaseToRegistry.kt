// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes release [version] of a catalog to a container registry (alpha, ADR 0032 section 7), as
 * the artifact `<prefix>/<namespace>/<catalogKey>:<version>`: its archive, exactly as released
 * (`ExportCatalogZip`), as the one layer.
 *
 * **One number names one release**, and most registries let a tag be overwritten without a word,
 * so the tag is looked up first. Already there with this release's fingerprint: it has been
 * published, and nothing is pushed (`alreadyPublished`). There with anything else: refused
 * ([ValidationCode.REGISTRY_TAG_TAKEN]). Looking first also matters on registries whose tags are
 * immutable, which refuse a second push of the same content. Between the look and the push another
 * installation could still publish the tag; an immutable-tag policy on the registry closes that.
 *
 * [Permission.CATALOG_PUBLISH], as sending a release to Epistola Exchange is: the release leaves
 * the installation, which being trusted to release it here does not cover. Exporting it needs
 * [Permission.CATALOG_VIEW] as well, checked on that dispatch.
 *
 * `SelfManagedTransaction`: the push is network traffic of up to the archive's size, which must not
 * hold a database connection; the export runs in a transaction of its own.
 */
data class PublishReleaseToRegistry(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val registry: String,
) : Command<RegistryPublication>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_PUBLISH

    init {
        validate("version", SemVer.parseOrNull(version)?.toString() == version.trim(), ValidationCode.REGISTRY_REFERENCE_INVALID) {
            "A release number is MAJOR.MINOR.PATCH, not '$version'."
        }
    }
}

/**
 * Where a release was published: [reference] is what a person would pull, `host/repository:tag`;
 * [digest] the manifest's. [alreadyPublished] when the registry had this release under its number
 * and nothing was pushed.
 */
data class RegistryPublication(
    val registry: String,
    val repository: String,
    val version: String,
    val digest: String,
    val alreadyPublished: Boolean,
    val reference: String,
)

@Component
class PublishReleaseToRegistryHandler(
    private val registries: CatalogRegistries,
    private val mapper: ObjectMapper,
) : CommandHandler<PublishReleaseToRegistry, RegistryPublication> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: PublishReleaseToRegistry): RegistryPublication {
        val registry = registries.named(command.registry)
        val version = command.version.trim()
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query() ?: throw CatalogNotFoundException(command.catalogKey)
        val release = ListCatalogReleases(command.tenantKey, command.catalogKey).query().firstOrNull { it.version == version }
            ?: throw CatalogVersionNotFoundException(command.catalogKey, version)
        val repository = registries.repository(registry, catalog.namespace, catalog.id.value)
        val client = registries.client(registry)
        fun result(digest: String, already: Boolean) = RegistryPublication(registry.name, repository, version, digest, already, "${registry.baseUri.authority}/$repository:$version")

        client.manifest(repository, version)?.let { existing ->
            if (existing.manifest.isCatalogRelease && existing.manifest.fingerprint == release.fingerprint) {
                return result(existing.digest, already = true)
            }
            throw ValidationException(
                "version",
                "${registry.name} already has $repository:$version with other content" +
                    (existing.manifest.fingerprint?.let { " (fingerprint $it, this release's ${release.fingerprint})" } ?: "") +
                    ". One number names one release: release the changes under a new number.",
                ValidationCode.REGISTRY_TAG_TAKEN,
            )
        }

        val archive = ExportCatalogZip(command.tenantKey, command.catalogKey, version).execute()
        val labels = ReleaseLabels(version, release.fingerprint, catalog.registry, catalog.namespace, catalog.id.value)
        val config = client.pushBlob(repository, OciMediaTypes.CONFIG, ReleaseArtifact.config(mapper, labels))
        val layer = client.pushBlob(repository, OciMediaTypes.ARCHIVE, archive.zipBytes, mapOf(OciAnnotations.IMAGE_TITLE to archive.filename))
        val digest = client.pushManifest(repository, version, ReleaseArtifact.manifest(mapper, labels, config, layer))
        log.info("Published {} {} of tenant {} to {} as {}@{}", catalog.id.value, version, command.tenantKey.value, registry.name, repository, digest)
        return result(digest, already = false)
    }
}
