// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogVersionSelector
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
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

/** A catalog to depend on, as an author declares it: its name and a version selector. */
data class DeclaredCatalogDependency(
    val registry: String,
    val namespace: String,
    val catalogKey: String,
    val version: String,
)

/**
 * Replaces the catalogs an authored catalog depends on (contract ADR 0003), in the order given.
 *
 * Each declaration names a catalog the tenant has and selects its releases with an npm range. A
 * declaration whose lock still satisfies its selector keeps it; any other is locked to the highest
 * release that matches, or left unlocked when none does yet, which releasing then refuses.
 */
data class UpdateCatalogDependencies(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val dependencies: List<DeclaredCatalogDependency>,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<List<CatalogDependencyDeclaration>>,
    RequiresPermission {
    // Which releases of its dependencies the next release is cut with is part of releasing.
    override val permission get() = Permission.TEMPLATE_PUBLISH

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
            validate("$field.version", CatalogVersionSelector.parseOrNull(dependency.version) != null, ValidationCode.CATALOG_DEPENDENCY_INVALID) {
                "Version '${dependency.version}' must be an npm version range of at most ${CatalogDependency.MAX_VERSION_LENGTH} characters"
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
) : CommandHandler<UpdateCatalogDependencies, List<CatalogDependencyDeclaration>> {

    override fun handle(command: UpdateCatalogDependencies): List<CatalogDependencyDeclaration> = jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
        val catalog = handle.findCatalog(command.tenantKey, command.catalogKey)
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.type == CatalogType.SUBSCRIBED) throw CatalogReadOnlyException(command.catalogKey)
        val existing = store.declarations(handle, command.tenantKey, command.catalogKey).associateBy { it.identity }

        val declarations = command.dependencies.mapIndexed { index, dependency ->
            val declared = CatalogDependencyDeclaration(dependency.registry, dependency.namespace, dependency.catalogKey, dependency.version)
            val field = "dependencies[$index]"
            if (declared.identity == Triple(catalog.registry, catalog.namespace, catalog.id.value)) {
                throw ValidationException(field, "A catalog cannot depend on itself", ValidationCode.CATALOG_DEPENDENCY_INVALID)
            }
            if (store.catalogNamed(handle, command.tenantKey, declared) == null) {
                throw ValidationException(field, "This tenant has no catalog $declared", ValidationCode.CATALOG_DEPENDENCY_INVALID)
            }
            val kept = existing[declared.identity]?.let { declared.copy(releaseVersion = it.releaseVersion, releaseFingerprint = it.releaseFingerprint) }
            if (kept != null && store.lockHolds(handle, command.tenantKey, kept)) {
                kept
            } else {
                val release = store.highestMatch(handle, command.tenantKey, declared)
                declared.copy(releaseVersion = release?.version, releaseFingerprint = release?.fingerprint)
            }
        }
        store.replace(handle, command.tenantKey, command.catalogKey, declarations)
        declarations
    }
}
