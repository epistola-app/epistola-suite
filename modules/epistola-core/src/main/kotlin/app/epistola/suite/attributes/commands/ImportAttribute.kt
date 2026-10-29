// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_DISPLAY_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Catalog-import UPSERT for variant attribute definitions.
 *
 * Carries `codeListCatalogKey` + `codeListSlug` when the source
 * `AttributeResource` declared a `codeListBinding`. The wire format's
 * `codeListBinding.catalogKey` may be null (meaning "same catalog as the
 * attribute"); the caller (`InstallFromCatalog.installAttribute`) resolves
 * that default before constructing this command, so by the time we get
 * here both keys are either both present or both null.
 */
data class ImportAttribute(
    val catalog: CatalogId,
    val slug: String,
    val displayName: String,
    val allowedValues: List<String> = emptyList(),
    /** The catalog of the bound code list, by its address (ADR 0034, section 9). */
    val codeListCatalog: CatalogId? = null,
    val codeListSlug: CodeListKey? = null,
    /** Producer metadata (contract `CatalogResource.metadata`), as received; null keeps what the draft holds. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceiling (#692): variant_attribute_definitions.display_name is VARCHAR(100).
        validate("displayName", displayName.length <= MAX_DISPLAY_NAME_COLUMN_LENGTH) { "Display name must be $MAX_DISPLAY_NAME_COLUMN_LENGTH characters or less" }
    }
}

@Component
class ImportAttributeHandler(
    private val jdbi: Jdbi,
    private val attributes: app.epistola.suite.attributes.AttributeStore,
) : CommandHandler<ImportAttribute, InstallStatus> {

    override fun handle(command: ImportAttribute): InstallStatus {
        val id = app.epistola.suite.common.ids.AttributeId(AttributeKey.of(command.slug), command.catalog)
        val existed = jdbi.withHandle<Boolean, Exception> { handle ->
            val binding = command.codeListSlug?.let { slug -> attributes.bindingOf(handle, command.catalog, app.epistola.suite.common.ids.CodeListId(slug, command.codeListCatalog ?: command.catalog)) }
            attributes.put(handle, id, app.epistola.suite.attributes.AttributeFields(command.displayName, command.allowedValues, binding, command.metadata), app.epistola.suite.security.currentUserIdOrNull()?.value)
        }
        return if (existed) InstallStatus.UPDATED else InstallStatus.INSTALLED
    }
}
