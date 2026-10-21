// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZipResult
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
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
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Installs release [version] of [namespace]/[catalogKey] from a container registry (alpha, ADR 0032
 * section 7), exactly as importing its archive does (`ImportCatalogZip`).
 *
 * What is checked, in order, before anything is installed:
 * 1. The tenant's catalog with this key, if any, is this catalog: same namespace, and not received
 *    from another source.
 * 2. The registry has the tag, as a catalog release, with an archive no larger than
 *    `epistola.catalog.max-zip-size`.
 * 3. The downloaded archive's SHA-256 and size are the layer's.
 * 4. The archive names the catalog that was asked for.
 *
 * The import then does what it does for a file: recomputes the fingerprint from the content (the
 * manifest's fingerprint label is never trusted), refuses the number when the tenant has it with
 * other content, refuses a release whose pinned dependencies are missing, and a catalog format that
 * is too new. Those surface as the import's own exceptions.
 *
 * A catalog the tenant does not have yet is created with source `oci:<registry>/<namespace>/<catalogKey>`;
 * one it has receives the release as any import does (ADR 0032). A first install that fails part-way
 * removes the catalog it created.
 *
 * [Permission.CATALOG_MANAGE], as importing an archive is. `SelfManagedTransaction`: the download
 * must not hold a database connection; the import runs in a transaction of its own.
 */
data class InstallReleaseFromRegistry(
    override val tenantKey: TenantKey,
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val version: String,
) : Command<RegistryInstallResult>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_MANAGE

    init {
        validate("version", SemVer.parseOrNull(version)?.toString() == version.trim(), ValidationCode.REGISTRY_REFERENCE_INVALID) {
            "A release number is MAJOR.MINOR.PATCH, not '$version'."
        }
    }
}

/** What an install did: [imported] is the import's own result; [digest] the manifest the archive came from. */
data class RegistryInstallResult(
    val catalogKey: CatalogKey,
    val version: String,
    val digest: String,
    val newlyInstalled: Boolean,
    val aborted: Boolean,
    val imported: ImportCatalogZipResult,
)

/** The rules installing from a registry and listing for it share. */
internal object RegistryInstallRules {
    /**
     * Why [local], the tenant's catalog with the requested key, is not the catalog [source] names,
     * or null when it is, or there is none. A catalog is addressed in a tenant by its key, so another
     * one under it would be overwritten or refused half-way; this says so first.
     */
    fun conflict(local: Catalog?, source: RegistrySourceUri): String? {
        if (local == null) return null
        if (local.namespace != source.namespace) {
            return "This tenant already uses the catalog ID '${local.id.value}' for ${local.namespace}/${local.id.value}, another catalog. Remove it first, or install into another tenant."
        }
        val origin = local.sourceUrl ?: return null
        if (origin == source.toString()) return null
        return "Catalog '${local.id.value}' receives its releases from $origin, not from ${source.registry}. Remove it first to install it from the registry."
    }
}

@Component
class InstallReleaseFromRegistryHandler(
    private val registries: CatalogRegistries,
    private val sizeLimits: CatalogSizeLimits,
    private val upstreamChecks: CatalogUpstreamCheckStore,
    private val mapper: ObjectMapper,
) : CommandHandler<InstallReleaseFromRegistry, RegistryInstallResult> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: InstallReleaseFromRegistry): RegistryInstallResult {
        val registry = registries.named(command.registry)
        val repository = registries.repository(registry, command.namespace, command.catalogKey)
        val namespace = command.namespace.trim()
        val catalogKey = CatalogKey.of(command.catalogKey.trim())
        val version = command.version.trim()
        val source = RegistrySourceUri(registry.name, namespace, catalogKey.value)

        val existing = GetCatalog(command.tenantKey, catalogKey).query()
        RegistryInstallRules.conflict(existing, source)?.let { throw ValidationException("catalogKey", it, ValidationCode.REGISTRY_CATALOG_KEY_TAKEN) }

        val client = registries.client(registry)
        val pulled = client.manifest(repository, version)
            ?: throw ValidationException("version", "${registry.name} has no release $version of $namespace/${catalogKey.value}.", ValidationCode.REGISTRY_RELEASE_UNAVAILABLE)
        val layer = pulled.manifest.archive?.takeIf { pulled.manifest.isCatalogRelease }
            ?: throw ValidationException("version", "$repository:$version in ${registry.name} is not a catalog release.", ValidationCode.REGISTRY_RELEASE_UNAVAILABLE)
        val archive = client.pullBlob(repository, layer, sizeLimits.maxZipSize.toBytes())
        requireNamesCatalog(archive, namespace, catalogKey, repository, version)

        val imported = ImportCatalogZip(
            tenantKey = command.tenantKey,
            zipBytes = archive,
            sourceUrl = if (existing == null) source.toString() else null,
            // Nobody is asked: an archive of an older wire schema is migrated, as any import may be.
            confirmMigration = true,
        ).execute()

        if (imported.aborted) {
            log.warn("Install of {} {} into tenant {} aborted", source, version, command.tenantKey.value)
            // A catalog the import created is removed again by the import itself.
            return RegistryInstallResult(catalogKey, version, pulled.digest, newlyInstalled = false, aborted = true, imported = imported)
        }
        if ((existing == null || existing.sourceUrl == source.toString()) && imported.newlyInstalled) {
            upstreamChecks.recordInstalled(command.tenantKey, catalogKey, version, layer.digest.removePrefix("sha256:"))
        }
        return RegistryInstallResult(catalogKey, version, pulled.digest, imported.newlyInstalled, aborted = false, imported = imported)
    }

    /**
     * The archive must be the catalog its repository and tag say: anyone who may push can put any
     * archive under any name, and installing it would land it under the name inside it.
     */
    private fun requireNamesCatalog(archive: ByteArray, namespace: String, catalogKey: CatalogKey, repository: String, version: String) {
        val manifest = catalogJson(archive)
            ?: throw ValidationException("version", "$repository:$version holds no catalog archive (no catalog.json).", ValidationCode.REGISTRY_ARCHIVE_REJECTED)
        val root = runCatching { mapper.readTree(manifest) }.getOrNull()
        val catalog = root?.path("catalog")
        val key = catalog?.path("key")?.takeIf { it.isString }?.stringValue()
        val ns = catalog?.path("namespace")?.takeIf { it.isString }?.stringValue()
        val release = root?.path("release")?.path("version")?.takeIf { it.isString }?.stringValue()
        if (key != catalogKey.value || (ns != null && ns != namespace) || release != version) {
            throw ValidationException(
                "version",
                "$repository:$version holds ${ns ?: "?"}/${key ?: "?"} ${release ?: "?"}, not $namespace/${catalogKey.value} $version; nothing was installed.",
                ValidationCode.REGISTRY_ARCHIVE_REJECTED,
            )
        }
    }

    private fun catalogJson(archive: ByteArray): ByteArray? = runCatching {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == "catalog.json" }?.let { zip.readNBytes(MAX_CATALOG_JSON_BYTES) }
        }
    }.getOrNull()

    private companion object {
        const val MAX_CATALOG_JSON_BYTES = 4 * 1024 * 1024
    }
}
