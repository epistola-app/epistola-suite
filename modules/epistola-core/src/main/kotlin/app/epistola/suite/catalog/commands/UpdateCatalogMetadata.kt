// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.AttributeAssignment
import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.catalog.protocol.CatalogPresentation
import app.epistola.suite.assets.AssetMediaCategory
import app.epistola.suite.assets.queries.ListAssets
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogPortableMetadata
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.findByTenantAndId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.commands.variants.validateAttributes
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI

data class UpdateCatalogMetadata(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val name: String,
    val description: String?,
    val attributes: List<AttributeAssignment> = emptyList(),
    val keywords: Set<String> = emptySet(),
    val presentation: CatalogPresentation? = null,
    val license: CatalogLicense? = null,
) : Command<Catalog>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE

    val portableMetadata = CatalogPortableMetadata(attributes, keywords, presentation, license)

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validatePortableMetadata()
    }

    private fun validatePortableMetadata() {
        val qualifiedAttributes = attributes.map { "${it.catalog}.${it.key}" }
        validate("attributes", qualifiedAttributes.distinct().size == qualifiedAttributes.size) {
            "Each catalog attribute can only be assigned once."
        }
        attributes.forEach {
            validate("attributes", CatalogKey.validateOrNull(it.catalog) != null && AttributeKey.validateOrNull(it.key) != null) {
                "Catalog attributes must use qualified catalog and attribute slugs."
            }
        }
        validate("keywords", keywords.all { it.isNotBlank() && it == it.trim() }) {
            "Keywords must be non-blank and cannot start or end with whitespace."
        }
        presentation?.let {
            val imageSlugs = listOfNotNull(it.iconAssetSlug) + it.imageAssetSlugs
            validate("presentation", imageSlugs.all { slug -> slug.isNotBlank() && slug == slug.trim() }) {
                "Presentation asset slugs must be non-blank and cannot start or end with whitespace."
            }
            validate("presentation", it.imageAssetSlugs.distinct().size == it.imageAssetSlugs.size) {
                "Presentation images must be unique."
            }
        }
        license?.let {
            validate("license", it.name.isNotBlank() && it.name == it.name.trim()) {
                "License name is required and cannot start or end with whitespace."
            }
            listOf(it.spdxExpression, it.copyrightText).filterNotNull().forEach { value ->
                validate("license", value.isNotBlank() && value == value.trim()) {
                    "Optional license fields cannot be blank or start or end with whitespace."
                }
            }
            it.url?.let { url ->
                val uri = runCatching { URI(url) }.getOrNull()
                validate("license", url == url.trim() && uri?.isAbsolute == true && uri.scheme.lowercase() in setOf("http", "https")) {
                    "License URL must be an absolute HTTP(S) URL."
                }
            }
        }
    }
}

@Component
class UpdateCatalogMetadataHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateCatalogMetadata, Catalog> {

    override fun handle(command: UpdateCatalogMetadata): Catalog {
        val existing = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        requireCatalogEditable(command.tenantKey, command.catalogKey)

        validateAttributes(
            TenantId(command.tenantKey),
            command.attributes.associate { "${it.catalog}.${it.key}" to it.value },
        )
        validatePresentationAssets(command)

        if (existing.name == command.name &&
            existing.description == command.description &&
            existing.portableMetadata == command.portableMetadata
        ) {
            return existing
        }

        val portableMetadataJson = objectMapper.writeValueAsString(command.portableMetadata)
        return jdbi.inTransaction<Catalog, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET name = :name, description = :description, portable_metadata = :portableMetadata::jsonb,
                    content_updated_at = NOW(), updated_at = NOW()
                WHERE tenant_key = :tenantKey AND id = :catalogKey
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .bind("portableMetadata", portableMetadataJson)
                .execute()

            handle.findByTenantAndId<Catalog>("catalogs", command.tenantKey, command.catalogKey.value)!!
        }
    }

    private fun validatePresentationAssets(command: UpdateCatalogMetadata) {
        val presentation = command.presentation ?: return
        val referenced = listOfNotNull(presentation.iconAssetSlug) + presentation.imageAssetSlugs
        if (referenced.isEmpty()) return

        val available = ListAssets(command.tenantKey, catalogKey = command.catalogKey).query()
            .filter { it.mediaType.category == AssetMediaCategory.IMAGE }
            .mapTo(mutableSetOf()) { it.id.value.toString() }
        validate("presentation", referenced.all(available::contains)) {
            "Presentation assets must be installed images from this catalog."
        }
    }
}
