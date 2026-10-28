// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.catalog.commands.SetCatalogPublicationSettings
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogEditability
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.exchange.GetCatalogPublicationState
import app.epistola.suite.handlers.CatalogHandler.CatalogMetadataAttributeField
import app.epistola.suite.handlers.CatalogViews.catalogImages
import app.epistola.suite.handlers.CatalogViews.chooseNamespaceIfOffered
import app.epistola.suite.handlers.CatalogViews.publicationPolicy
import app.epistola.suite.handlers.CatalogViews.section
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.validation.FieldLimits
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

private const val CATALOG_LOCALE_ATTRIBUTE = "system.locale"

/** A catalog's metadata: its name, description, keywords, presentation, licence and locale. */
@Component
class CatalogMetadataHandler {
    // Logs under CatalogHandler, the name these lines had before the handler was split.
    private val logger = LoggerFactory.getLogger(CatalogHandler::class.java)

    fun metadataForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        val section = CatalogMetadataSection.from(request.param("section").orElse(CatalogMetadataSection.DETAILS.value))
        return fragmentOrPage(request, "catalogs/metadata", "dialog", catalogMetadataModel(catalog, section))
    }

    fun updateMetadata(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalog = request.catalog(tenantId)
        requirePermission(tenantId.key, Permission.CATALOG_MANAGE)
        val section = CatalogMetadataSection.from(request.servletRequest().getParameter("section").orEmpty())
        val params = request.servletRequest().parameterMap

        return try {
            val found = catalogForMetadata(catalog)
            val current = found.catalogMetadata

            if (section == CatalogMetadataSection.PUBLICATION) {
                SetCatalogPublicationSettings(
                    catalog = catalog,
                    policy = publicationPolicy(request.params().getFirst("publicationPolicy")),
                ).execute()
                // The namespace is a publishing decision, not catalog metadata, and carries its own
                // permission — so it travels as its own command and is simply absent for anyone who
                // may not set it.
                chooseNamespaceIfOffered(request, catalog)
                return request.htmx {
                    dialogRedirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/browse")
                    onFullPage { redirect("/tenants/${tenantId.key}/catalogs/${catalog.address}/browse") }
                }
            }

            UpdateCatalogMetadata(
                catalog = catalog,
                name = if (section == CatalogMetadataSection.DETAILS) {
                    request.servletRequest().getParameter("name").orEmpty()
                } else {
                    found.name
                },
                description = if (section == CatalogMetadataSection.DETAILS) {
                    request.servletRequest().getParameter("description")?.trim()?.ifBlank { null }
                } else {
                    found.description
                },
                attributes = if (section == CatalogMetadataSection.ATTRIBUTES) {
                    parseLocaleMetadataAttribute(params, current.attributes)
                } else {
                    current.attributes
                },
                keywords = if (section == CatalogMetadataSection.KEYWORDS) parseMetadataKeywords(request) else current.keywords,
                presentation = if (section == CatalogMetadataSection.PRESENTATION) parseCatalogPresentation(request) else current.presentation,
                license = if (section == CatalogMetadataSection.LICENSE) parseCatalogLicense(request) else current.license,
            ).execute()

            // Details go out with a version, so they are edited on a draft's page: back to it.
            val back = if (GetCatalogEditability(catalog).query().editable) {
                CatalogPages.draft(tenantId, request.catalogSegment())
            } else {
                CatalogPages.browse(tenantId, request.catalogSegment())
            }
            request.htmx {
                dialogRedirect(back)
                onFullPage { redirect(back) }
            }
        } catch (e: Exception) {
            logger.warn("Failed to update catalog metadata: {}", e.message)
            // Into the dialog's error slot; what was typed stays.
            request.htmx {
                globalFormError("catalog-metadata-error", e.message ?: "Failed to save catalog metadata.")
                onFullPage { redirect(CatalogPages.browse(tenantId, request.catalogSegment())) }
            }
        }
    }

    private fun parseLocaleMetadataAttribute(
        params: Map<String, Array<String>>,
        current: List<AttributeAssignment>,
    ): List<AttributeAssignment> {
        val fieldName = "attribute_$CATALOG_LOCALE_ATTRIBUTE"
        require(params.keys.none { it.startsWith("attribute_") && it != fieldName }) {
            "Only the locale discovery attribute can be changed."
        }
        val preserved = current.filterNot { "${it.catalogKey}.${it.key}" == CATALOG_LOCALE_ATTRIBUTE }
        val locale = params[fieldName]?.firstOrNull()?.trim()?.ifBlank { null }
        return if (locale == null) preserved else preserved + AttributeAssignment("system", "locale", locale)
    }

    private fun parseMetadataKeywords(request: ServerRequest): Set<String> {
        val keywords = request.servletRequest().getParameter("keywords").orEmpty()
            .lines().map(String::trim).filter(String::isNotEmpty)
        require(keywords.distinct().size == keywords.size) { "Keywords must be unique (case is significant)." }
        return LinkedHashSet(keywords)
    }

    private fun parseCatalogPresentation(request: ServerRequest): CatalogPresentation? {
        val imageAssetSlugs = request.servletRequest().getParameterValues("imageAssetSlugs")
            ?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val iconAssetSlug = request.servletRequest().getParameter("iconAssetSlug")?.trim()?.ifBlank { null }
        return if (iconAssetSlug != null || imageAssetSlugs.isNotEmpty()) {
            CatalogPresentation(iconAssetSlug, imageAssetSlugs)
        } else {
            null
        }
    }

    private fun parseCatalogLicense(request: ServerRequest): CatalogLicense? {
        val licenseName = request.servletRequest().getParameter("licenseName")?.trim().orEmpty()
        return if (licenseName.isNotEmpty() ||
            listOf("licenseSpdx", "licenseUrl", "licenseCopyright").any { !request.servletRequest().getParameter(it).isNullOrBlank() }
        ) {
            CatalogLicense(
                name = licenseName,
                spdxExpression = request.servletRequest().getParameter("licenseSpdx")?.trim()?.ifBlank { null },
                url = request.servletRequest().getParameter("licenseUrl")?.trim()?.ifBlank { null },
                copyrightText = request.servletRequest().getParameter("licenseCopyright")?.trim()?.ifBlank { null },
            )
        } else {
            null
        }
    }

    private fun catalogMetadataModel(
        catalogId: app.epistola.suite.common.ids.CatalogId,
        section: CatalogMetadataSection,
    ): Map<String, Any?> {
        val tenantKey = catalogId.tenantKey
        val catalog = catalogForMetadata(catalogId)
        val currentAttributes = catalog.catalogMetadata.attributes.associate { "${it.catalogKey}.${it.key}" to it.value }
        val descriptors = buildAttributeDescriptors(ListAttributeDefinitions(app.epistola.suite.common.ids.TenantId(tenantKey)).query())
            .filter { it.qualifiedKey == CATALOG_LOCALE_ATTRIBUTE }
        val images = catalogImages(catalogId)
        val gallery = catalog.catalogMetadata.presentation?.imageKeys.orEmpty()

        return mapOf(
            "tenantId" to tenantKey,
            "catalog" to catalog,
            "section" to section.value,
            "sectionTitle" to section.title,
            "attributeFields" to descriptors.map { CatalogMetadataAttributeField(it, currentAttributes[it.qualifiedKey]) },
            "keywords" to catalog.catalogMetadata.keywords.inDisplayOrder(),
            "maxKeywords" to FieldLimits.MAX_KEYWORDS,
            "maxKeywordLength" to FieldLimits.MAX_KEYWORD_LENGTH,
            "images" to images,
            // The trailing "" is one empty picker to add the next image with. It is only drawn
            // when there are images to pick, because the section itself is guarded on that.
            "gallerySlugs" to (gallery + ""),
            "publication" to GetCatalogPublicationState(catalogId).query(),
        )
    }

    private fun catalogForMetadata(
        catalogId: app.epistola.suite.common.ids.CatalogId,
    ) = GetCatalog(catalogId).query()
        ?.also { catalog ->
            val editability = GetCatalogEditability(catalog.catalogId).query()
            require(editability.editable) { editability.reason?.explanation ?: "This catalog cannot be changed here." }
        }
        ?: throw IllegalArgumentException("Catalog not found: ${catalogId.address}")

    private enum class CatalogMetadataSection(val value: String, val title: String) {
        DETAILS("details", "General details"),
        ATTRIBUTES("attributes", "Locale"),
        KEYWORDS("keywords", "Keywords"),
        PRESENTATION("presentation", "Presentation"),
        LICENSE("license", "License"),
        PUBLICATION("publication", "Exchange publication"),
        ;

        companion object {
            fun from(value: String): CatalogMetadataSection = entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown catalog metadata section: $value")
        }
    }
}
