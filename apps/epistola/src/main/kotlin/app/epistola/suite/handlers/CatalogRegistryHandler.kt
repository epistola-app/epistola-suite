// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogImportConflictException
import app.epistola.suite.catalog.commands.CatalogImportDependenciesMissingException
import app.epistola.suite.catalog.commands.CatalogImportValidationException
import app.epistola.suite.catalog.commands.ReleaseRenderingDefaultsTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.registry.InstallReleaseRegistryRelease
import app.epistola.suite.catalog.registry.ListReleaseRegistries
import app.epistola.suite.catalog.registry.ListReleaseRegistryReleases
import app.epistola.suite.catalog.registry.PublishReleaseToReleaseRegistry
import app.epistola.suite.catalog.registry.ReleaseRegistryException
import app.epistola.suite.catalog.registry.ReleaseRegistryList
import app.epistola.suite.catalog.sources.CatalogSourceUnavailableException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Moving catalog releases through a release registry (alpha, ADR 0032 section 7, contract ADR 0008):
 * a configured container registry or the Epistola Exchange the tenant is connected to. _Get a
 * release from a registry_ beside importing a file, and _Publish to a registry_ on a release.
 * Offered only when the tenant has a release registry.
 *
 * Getting a release takes [Permission.CATALOG_MANAGE], what [InstallReleaseRegistryRelease] and the
 * archive import it dispatches require: receiving a release writes no draft. Publishing takes
 * [Permission.CATALOG_PUBLISH].
 */
@Component
class CatalogRegistryHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** The _Get a release_ dialog; with registry, namespace and key given (an update link), its versions too. */
    fun getDialog(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requireInstallPermissions(tenantKey)
        val model = getDialogModel(request, tenantKey)
        return request.htmx {
            fragment("catalogs/registry", "get-dialog") { putAll(model) }
            onFullPage {
                page("catalogs/list") {
                    "pageTitle" to "Catalogs - Epistola"
                    "activeNavSection" to "catalogs"
                    catalogListModel(tenantKey)
                    "resourceRelocationEnabled" to (ResolveFeatureToggles(tenantKey).query()[KnownFeatures.RESOURCE_RELOCATION] == true)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/registry :: get-dialog"
                    putAll(model)
                }
            }
        }
    }

    /** The versions a registry holds of the catalog named in the dialog, swapped in below its fields. */
    fun releases(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requireInstallPermissions(tenantKey)
        return fragmentOrPage(request, "catalogs/registry", "releases", releasesModel(request, tenantKey))
    }

    fun install(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requireInstallPermissions(tenantKey)
        val registry = request.param("registry").orElse("")
        val namespace = request.param("namespace").orElse("")
        val catalogKey = request.param("catalogKey").orElse("")
        val version = request.param("version").orElse("").trim()
        if (version.isEmpty()) return installError(request, "Choose the release to get.")
        return try {
            val result = InstallReleaseRegistryRelease(tenantKey, registry, namespace, catalogKey, version).execute()
            if (result.aborted) {
                return installError(request, "$version was not installed: a resource in it could not be imported. Nothing was changed.")
            }
            request.htmx {
                dialogSuccess("catalogs/list", "catalog-list", "/tenants/${tenantKey.value}/catalogs") { catalogListModel(tenantKey) }
                onFullPage { redirect("/tenants/${tenantKey.value}/catalogs?saved=true") }
            }
        } catch (e: Exception) {
            installError(request, refusal(e, "The release could not be installed"))
        }
    }

    /** The _Publish to a registry_ dialog for one release. */
    fun publishDialog(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_PUBLISH)
        val catalogKey = request.catalogId()
        return fragmentOrPage(
            request,
            "catalogs/registry",
            "publish-dialog",
            mapOf(
                "tenantId" to tenantKey,
                "catalogId" to catalogKey.value,
                "version" to request.param("version").orElse(""),
                "registries" to ListReleaseRegistries(tenantKey).query(),
            ),
        )
    }

    fun publish(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_PUBLISH)
        val catalogKey = request.catalogId()
        val version = request.param("version").orElse("").trim()
        val registry = request.param("registry").orElse("")
        return try {
            val published = PublishReleaseToReleaseRegistry(tenantKey, catalogKey, version, registry).execute()
            request.htmx {
                dialogReveal("catalogs/registry", "publish-done", "#registry-publish-form") { "publication" to published }
                onFullPage { redirect("/tenants/${tenantKey.value}/catalogs/${catalogKey.value}/releases/$version") }
            }
        } catch (e: Exception) {
            request.htmx {
                dialogFormError("registry-publish-error", refusal(e, "The release could not be published"))
                onFullPage { redirect("/tenants/${tenantKey.value}/catalogs/${catalogKey.value}/releases/$version") }
            }
        }
    }

    private fun getDialogModel(request: ServerRequest, tenantKey: TenantKey): Map<String, Any?> {
        val registries = ListReleaseRegistries(tenantKey).query()
        val asked = request.param("namespace").isPresent && request.param("catalogKey").isPresent
        return mapOf(
            "tenantId" to tenantKey,
            "registries" to registries,
            "registry" to (request.param("registry").orElse(null) ?: registries.registries.firstOrNull()?.name),
            "namespace" to request.param("namespace").orElse(""),
            "catalogKey" to request.param("catalogKey").orElse(""),
        ) + if (asked && registries.available) releasesModel(request, tenantKey) else emptyMap()
    }

    private fun releasesModel(request: ServerRequest, tenantKey: TenantKey): Map<String, Any?> {
        val registry = request.param("registry").orElse(null)
            ?: ListReleaseRegistries(tenantKey).query().registries.firstOrNull()?.name.orEmpty()
        val namespace = request.param("namespace").orElse("").trim()
        val catalogKey = request.param("catalogKey").orElse("").trim()
        val base = mapOf("tenantId" to tenantKey, "asked" to true)
        if (namespace.isEmpty() || catalogKey.isEmpty()) return base + ("releasesError" to "Enter the catalog's namespace and key.")
        return try {
            base + ("found" to ListReleaseRegistryReleases(tenantKey, registry, namespace, catalogKey).query())
        } catch (e: Exception) {
            base + ("releasesError" to refusal(e, "The registry could not be asked"))
        }
    }

    /** A refusal in words for the dialog; anything unexpected is logged and named as such. */
    private fun refusal(e: Exception, unexpected: String): String = when (e) {
        is ValidationException -> e.message

        is ReleaseRegistryException -> e.message ?: unexpected

        is CatalogSourceUnavailableException -> e.message ?: unexpected

        is CatalogImportConflictException -> e.message ?: unexpected

        is CatalogImportDependenciesMissingException ->
            "This release needs releases of other catalogs that this tenant does not have: " +
                e.missing.joinToString { "${it.catalogKey} ${it.releaseVersion}" } + ". Get those first."

        is CatalogImportValidationException -> "The release's archive does not check out (" + e.message + "). Nothing was installed."

        is CatalogSchemaException -> e.message ?: unexpected

        is ReleaseRenderingDefaultsTooNewException -> e.message ?: unexpected

        is CatalogVersionNotFoundException -> e.message ?: unexpected

        is IllegalArgumentException -> e.message ?: unexpected

        else -> {
            logger.warn("{}: {}", unexpected, e.message, e)
            "$unexpected. ${e.message.orEmpty()}".trim()
        }
    }

    private fun installError(request: ServerRequest, message: String): ServerResponse = request.htmx {
        dialogFormError("registry-install-error", message)
        onFullPage { redirect("/tenants/${request.tenantId().key.value}/catalogs") }
    }

    private fun requireInstallPermissions(tenantKey: TenantKey) {
        requirePermission(tenantKey, Permission.CATALOG_MANAGE)
    }

    companion object {
        /**
         * Whether the release page offers _Publish to a registry_: the tenant has a release registry, and the
         * principal may publish. The one name the template gates on.
         */
        fun canPublish(tenantKey: TenantKey, registries: ReleaseRegistryList): Boolean = registries.available && SecurityContext.current().hasPermission(tenantKey, Permission.CATALOG_PUBLISH)

        /** Whether the catalogs page offers _Get a release from a registry_. */
        fun canInstall(tenantKey: TenantKey, registries: ReleaseRegistryList): Boolean = registries.available &&
            SecurityContext.current().hasPermission(tenantKey, Permission.CATALOG_MANAGE)
    }
}
