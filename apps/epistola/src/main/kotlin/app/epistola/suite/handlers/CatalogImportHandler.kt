// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.commands.CatalogNameRequiredException
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.ReleaseRenderingDefaultsTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooNewException
import app.epistola.suite.catalog.migrations.CatalogSchemaTooOldException
import app.epistola.suite.catalog.migrations.CatalogSchemaUnknownException
import app.epistola.suite.catalog.registry.ListReleaseRegistries
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.isHtmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import jakarta.servlet.http.Part
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.util.MultiValueMap
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Importing a catalog archive from the catalog list (_Import ZIP_): the dialog and the upload.
 * The import receives the release ([ImportCatalogZip], `CATALOG_MANAGE`); an archive from before
 * wire v8 names no catalog, so the operator is asked for its registry and namespace, proposed as
 * the default registry and this tenant's namespace, never decided silently.
 */
@Component
class CatalogImportHandler(
    private val identity: CatalogIdentityProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * The Import-ZIP dialog, mirroring the create and subscribe dialogs: an in-app
     * trigger (hx-get → #dialog-mount) gets just the `dialog` fragment; direct
     * navigation / boost gets the host list page with the dialog embedded in its
     * mount (openDialog=true), opened on load by JS. Gated on CATALOG_MANAGE —
     * the same permission [ImportCatalogZip] enforces for receiving a release.
     */
    fun importForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        return request.htmx {
            fragment("catalogs/import", "dialog") {
                "tenantId" to tenantId.key
                "registryInstallAvailable" to CatalogRegistryHandler.canInstall(tenantId.key, ListReleaseRegistries(tenantId.key).query())
            }
            onFullPage {
                page("catalogs/list") {
                    catalogPageModel(tenantId.key)
                    "openDialog" to true
                    "openDialogFragment" to "catalogs/import :: dialog"
                }
            }
        }
    }

    fun importZip(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)

        val multipartData = request.multipartData()
        val filePart = multipartData["file"]?.firstOrNull()
            ?: return importError(request, "No file provided")

        val zipBytes = filePart.inputStream.use { it.readAllBytes() }
        // The file's name, recorded on the release it brings as where it came from.
        val fileName = filePart.submittedFileName?.takeIf { it.isNotBlank() }?.take(MAX_FILE_NAME)

        // Set when the operator confirmed updating a ZIP that is below the
        // current catalog schema version but migratable (re-submitted from the
        // "update?" prompt).
        val confirmMigration = multipartData.text("confirmMigration").equals("true", ignoreCase = true)
        // Set when the operator named the catalog of an archive from before wire v8 (re-submitted
        // from the "name it" prompt), which names none; for a v8 archive they are not sent.
        val registry = multipartData.text("registry")
        val namespace = multipartData.text("namespace")

        return try {
            val result = ImportCatalogZip(
                tenantKey = tenantId.key,
                zipBytes = zipBytes,
                // An import receives the release (ADR 0032): it is installed beside the catalog's
                // others, and a catalog the tenant does not have yet is created holding it.
                confirmMigration = confirmMigration,
                registry = registry,
                namespace = namespace,
                provenance = app.epistola.suite.catalog.releases.ReleaseProvenance(app.epistola.suite.catalog.releases.ReleaseChannel.UPLOAD, fileName),
            ).execute()

            if (result.aborted) {
                val failed = result.results.filter { it.status == InstallStatus.FAILED }.joinToString(", ") { "${it.type}/${it.slug}" }
                return importError(request, "The release could not be installed, and nothing was changed: these resources failed: $failed")
            }

            if (request.isHtmx) {
                request.htmx {
                    dialogRedirect("/tenants/${tenantId.key}/catalogs/${result.catalog?.address ?: result.catalogKey}/browse")
                    onFullPage { redirect("/tenants/${tenantId.key}/catalogs/${result.catalog?.address ?: result.catalogKey}/browse") }
                }
            } else {
                redirect("/tenants/${tenantId.key}/catalogs/${result.catalog?.address ?: result.catalogKey}/browse")
            }
        } catch (e: CatalogSchemaException) {
            // The uploaded catalog's wire format is too new, too old, or
            // unrecognised — the migrator rejected it before binding. Render the
            // actionable remediation message inline, so the operator knows whether to upgrade
            // this instance or re-export from a current source.
            logger.warn("Import blocked by catalog wire-format version: ${e.message}")
            val title = when (e) {
                is CatalogSchemaTooNewException -> "Import blocked: catalog format is too new"
                is CatalogSchemaTooOldException -> "Import blocked: catalog format is too old"
                is CatalogSchemaUnknownException -> "Import blocked: unrecognised catalog format"
            }
            fragmentOrPage(
                request,
                "catalogs/import",
                "import-schema-error",
                mapOf(
                    "schemaErrorTitle" to title,
                    "schemaErrorDetail" to (e.message ?: "Incompatible catalog wire format."),
                ),
            )
        } catch (e: ReleaseRenderingDefaultsTooNewException) {
            // The release renders with defaults a newer Epistola has: the same remediation as a wire
            // format that is too new.
            logger.warn("Import blocked by rendering defaults version: ${e.message}")
            fragmentOrPage(
                request,
                "catalogs/import",
                "import-schema-error",
                mapOf(
                    "schemaErrorTitle" to "Import blocked: this release needs a newer Epistola",
                    "schemaErrorDetail" to (e.message ?: "The release renders with rendering defaults this Epistola does not have."),
                ),
            )
        } catch (e: CatalogNameRequiredException) {
            // An archive from before wire v8 names no catalog: the importer proposes the default
            // registry and this tenant's namespace, and the operator decides. The prompt's fields sit
            // inside the form, so its button re-submits the same file with them, migration confirmed.
            fragmentOrPage(
                request,
                "catalogs/import",
                "import-name-required",
                mapOf(
                    "registry" to (registry ?: identity.defaultRegistry),
                    "namespace" to (namespace ?: CatalogIdentityProperties.defaultNamespace(tenantId.key)),
                ),
            )
        } catch (e: CatalogMigrationConfirmationRequiredException) {
            // A ZIP below the current catalog schema version but migratable:
            // updating mutates the imported content, so prompt before importing. The
            // "Update" button re-submits the same form with confirmMigration=true.
            fragmentOrPage(
                request,
                "catalogs/import",
                "import-migration-confirm",
                mapOf(
                    "tenantId" to tenantId.key,
                    "fromSchemaVersion" to e.fromVersion,
                    "toSchemaVersion" to e.toVersion,
                    "migrationNotices" to e.notices,
                    // Named already (a migrated archive from before wire v8): kept for the re-submit.
                    "registry" to registry,
                    "namespace" to namespace,
                ),
            )
        } catch (e: Exception) {
            logger.warn("Failed to import catalog from ZIP: ${e.message}", e)
            importError(request, e.message ?: "Failed to import catalog")
        }
    }

    /**
     * Plain single-message import failures go through the global form-error
     * slot (shaped 422; see HtmxDsl.globalFormError). The structured import
     * responses (schema error, migration confirm) keep
     * rendering into the form's dedicated #import-error target instead — the
     * text-only slot cannot hold them. Because the shaped response's primary
     * swap is `none`, stale structured content in #import-error is OOB-reset
     * in the same response.
     */
    private fun importError(request: ServerRequest, error: String): ServerResponse = request.htmx {
        globalFormError("import-catalog-error", error)
        oob("catalogs/import", "import-error-reset")
        // Defensive only: unlike the subscribe/create forms, the import form is
        // HTMX-only (no method/action fallback — a file upload has nothing to
        // restore on a re-render anyway), so a genuine non-HTMX POST cannot reach
        // this. Kept consistent with registerError so the branch stays correct if a
        // fallback is ever added; `error` renders in the dialog's form-error slot.
        onFullPage {
            page(422, "catalogs/list") {
                catalogPageModel(request.tenantId().key)
                "openDialog" to true
                "openDialogFragment" to "catalogs/import :: dialog"
                "error" to error
            }
        }
    }

    /** The catalog list page behind the dialog, as the other catalog dialogs render it. */
    private fun ModelBuilder.catalogPageModel(tenantKey: TenantKey) {
        "pageTitle" to "Catalogs - Epistola"
        "activeNavSection" to "catalogs"
        catalogListModel(tenantKey)
        "resourceRelocationEnabled" to (ResolveFeatureToggles(tenantKey).query()[KnownFeatures.RESOURCE_RELOCATION] == true)
    }

    /** A text field of the multipart form, trimmed; null when absent or blank. */
    private fun MultiValueMap<String, Part>.text(name: String): String? = this[name]?.firstOrNull()?.let { String(it.inputStream.readAllBytes()).trim() }?.ifBlank { null }

    private companion object {
        /** The longest file name a release records as where it came from. */
        const val MAX_FILE_NAME = 255
    }
}
