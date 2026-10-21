// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.catalog.protocol.CatalogReleaseVersions
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A catalog to depend on, as an author declares it: its name and the exact release to render with,
 * or, in a draft, a draft of it by its id ([versionId]). Exactly one of the two.
 */
data class DeclaredCatalogDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val releaseVersion: String? = null,
    val versionId: String? = null,
)

/**
 * Replaces the catalogs an authored catalog depends on (contract ADR 0003), in the order given.
 *
 * Each declaration names a catalog the tenant has and pins an exact release of it that the tenant
 * has, recorded with that release's fingerprint, or names one of that catalog's drafts, to build on
 * work not released yet (contract ADR 0002). A range is not a declaration (contract 2.0).
 */
data class UpdateCatalogDependencies(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val dependencies: List<DeclaredCatalogDependency>,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
    /** The revision of the version the caller read ([CatalogVersion.revision]); null: no check. */
    val expectedRevision: Long? = null,
) : CatalogVersionScoped,
    Command<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    // Which releases of its dependencies the next release is cut with is part of releasing.
    override val permission get() = Permission.TEMPLATE_EDIT

    init {
        dependencies.forEachIndexed { index, dependency ->
            val field = "dependencies[$index]"
            validate("$field.registry", CatalogNamespaces.isValidRegistry(dependency.registry), ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                "Registry '${dependency.registry}' must be a DNS name written in full"
            }
            validate("$field.namespace", CatalogNamespaces.isValid(dependency.namespace), ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                "Namespace '${dependency.namespace}' must be a lowercase slug"
            }
            validate("$field.catalogKey", runCatching { CatalogKey.of(dependency.catalogKey) }.isSuccess, ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                "Catalog key '${dependency.catalogKey}' is not a valid catalog key"
            }
            validate("$field", (dependency.releaseVersion == null) != (dependency.versionId == null), ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                "Name either a release of ${dependency.catalogKey} or one of its drafts, not both or neither"
            }
            dependency.releaseVersion?.let { release ->
                validate("$field.releaseVersion", CatalogReleaseVersions.isValid(release), ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                    "Release '$release' must be a release number, MAJOR.MINOR.PATCH, of at most ${CatalogReleaseVersions.MAX_LENGTH} characters"
                }
            }
            dependency.versionId?.let { draft ->
                validate("$field.versionId", DraftRef.isKey(draft), ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                    "Draft '$draft' is not a version id"
                }
            }
        }
        val duplicate = dependencies.groupBy { Triple(it.registry, it.namespace, it.catalogKey) }.values.firstOrNull { it.size > 1 }?.first()
        validate("dependencies", duplicate == null, ValidationCode.CATALOG_DEPENDENCY_INVALID) {
            "Catalog ${duplicate!!.registry}~${duplicate.namespace}/${duplicate.catalogKey} is declared more than once"
        }
    }
}

@Component
class UpdateCatalogDependenciesHandler(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
    private val drafts: app.epistola.suite.catalog.versions.CatalogDrafts,
) : CommandHandler<UpdateCatalogDependencies, List<CatalogDependencyDeclaration>> {

    override fun handle(command: UpdateCatalogDependencies): List<CatalogDependencyDeclaration> = jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val catalog = handle.findCatalog(command.tenantKey, command.catalogKey)
            ?: throw CatalogNotFoundException(command.catalogKey)
        requireCatalogEditable(command.tenantKey, command.catalogKey)
        drafts.draft(handle, command.tenantKey, command.catalogKey)?.let { draft ->
            drafts.requireRevision(handle, command.tenantKey, draft.versionId, command.expectedRevision, "Catalog version '${draft.label}' of '${command.catalogKey.value}'")
        }

        val declarations = command.dependencies.mapIndexed { index, dependency ->
            val declared = CatalogDependencyDeclaration(dependency.registry, dependency.namespace, dependency.catalogKey)
            val field = "dependencies[$index]"
            if (declared.identity == Triple(catalog.registry, catalog.namespace, catalog.id.value)) {
                throw ValidationException(field, "A catalog cannot depend on itself", ValidationCode.CATALOG_DEPENDENCY_INVALID)
            }
            if (store.catalogNamed(handle, command.tenantKey, declared) == null) {
                throw ValidationException(field, "This tenant has no catalog $declared", ValidationCode.CATALOG_DEPENDENCY_INVALID)
            }
            val draftKey = dependency.versionId
            if (draftKey != null) {
                val (_, name) = store.draftOf(handle, command.tenantKey, declared, draftKey)
                    ?: throw ValidationException("$field.versionId", "${declared.catalogKey} has no draft $draftKey", ValidationCode.CATALOG_DEPENDENCY_INVALID)
                return@mapIndexed declared.copy(draftId = draftKey, draftName = name)
            }
            val version = requireNotNull(dependency.releaseVersion)
            val release = store.release(handle, command.tenantKey, declared, version)
                ?: throw ValidationException("$field.releaseVersion", "This tenant has no release $version of ${declared.catalogKey}", ValidationCode.CATALOG_DEPENDENCY_INVALID)
            declared.copy(releaseVersion = release.version, releaseFingerprint = release.fingerprint)
        }
        store.replace(handle, command.tenantKey, command.catalogKey, declarations)
        declarations
    }
}
