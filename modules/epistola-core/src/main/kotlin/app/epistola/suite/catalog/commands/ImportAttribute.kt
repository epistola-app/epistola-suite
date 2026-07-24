// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.AttributeKey
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
    val tenantId: TenantId,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val slug: String,
    val displayName: String,
    val allowedValues: List<String> = emptyList(),
    val codeListCatalogKey: CatalogKey? = null,
    val codeListSlug: CodeListKey? = null,
) : Command<InstallStatus>,
    RequiresPermission {
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
    private val objectMapper: ObjectMapper,
) : CommandHandler<ImportAttribute, InstallStatus> {

    override fun handle(command: ImportAttribute): InstallStatus {
        val attributeKey = AttributeKey.of(command.slug)
        val allowedValuesJson = objectMapper.writeValueAsString(command.allowedValues)

        // Single round-trip on one connection: the upsert reports INSTALLED vs
        // UPDATED via `xmax = 0` (true only for the freshly inserted row), so we
        // no longer need a separate existence SELECT on its own connection.
        val inserted = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                INSERT INTO variant_attribute_definitions (
                    id, tenant_key, catalog_key, display_name, allowed_values,
                    code_list_catalog_key, code_list_slug,
                    created_at, updated_at
                )
                VALUES (
                    :id, :tenantKey, :catalogKey, :displayName, :allowedValues::jsonb,
                    :codeListCatalogKey, :codeListSlug,
                    NOW(), NOW()
                )
                ON CONFLICT (tenant_key, catalog_key, id) DO UPDATE
                SET display_name           = :displayName,
                    allowed_values         = :allowedValues::jsonb,
                    code_list_catalog_key  = :codeListCatalogKey,
                    code_list_slug         = :codeListSlug,
                    updated_at          = NOW()
                RETURNING (xmax = 0) AS inserted
                """,
            )
                .bind("id", attributeKey)
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("displayName", command.displayName)
                .bind("allowedValues", allowedValuesJson)
                .bind("codeListCatalogKey", command.codeListCatalogKey)
                .bind("codeListSlug", command.codeListSlug)
                .mapTo(Boolean::class.java)
                .one()
        }

        return if (inserted) InstallStatus.INSTALLED else InstallStatus.UPDATED
    }
}
