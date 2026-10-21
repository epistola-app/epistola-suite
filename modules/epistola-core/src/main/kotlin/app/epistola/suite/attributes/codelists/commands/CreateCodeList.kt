// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionScoped
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Creates a code list in a catalog that can be changed here.
 *
 * For `INLINE` source the caller passes initial entries directly; for `URL`
 * the caller passes a URL plus optional auth and `entries` is empty (entries
 * are populated on first refresh via `RefreshCodeList`). The URL is checked against the
 * outbound source rules before it is stored (`CodeListClient.requireAllowedSourceUrl`), so a
 * `classpath:` URL, a `file:` path outside the configured roots or a non-public address is a
 * validation error here rather than a refresh failure later.
 */
data class CreateCodeList(
    val id: CodeListId,
    val displayName: String,
    val description: String? = null,
    val sourceType: CodeListSource,
    val sourceUrl: String? = null,
    val authType: AuthType = AuthType.NONE,
    val credential: String? = null,
    val entries: List<CodeListEntry> = emptyList(),
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogVersionRef? = null,
) : CatalogVersionScoped,
    Command<CodeList>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey

    init {
        validate("displayName", displayName.isNotBlank()) { "Display name is required" }
        validate("displayName", displayName.length <= MAX_NAME_LENGTH) { "Display name must be $MAX_NAME_LENGTH characters or less" }
        when (sourceType) {
            CodeListSource.INLINE -> {
                validate("sourceUrl", sourceUrl == null) { "Inline code lists must not have a source URL" }
                validate("entries", entries.isNotEmpty()) { "Inline code lists must have at least one entry" }
            }

            CodeListSource.URL, CodeListSource.CLASSPATH -> {
                validate("sourceUrl", !sourceUrl.isNullOrBlank()) { "Source URL is required for ${sourceType.name} code lists" }
            }
        }
        validateCodeListEntries(entries)
    }
}

@Component
class CreateCodeListHandler(
    private val jdbi: Jdbi,
    private val codeListClient: CodeListClient,
) : CommandHandler<CreateCodeList, CodeList> {
    override fun handle(command: CreateCodeList): CodeList {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        if (command.sourceType != CodeListSource.INLINE) {
            command.sourceUrl?.let { codeListClient.requireAllowedSourceUrl(it) }
        }
        return executeOrThrowDuplicate("code-list", command.id.key.value) {
            jdbi.inTransaction<CodeList, Exception> { handle ->
                val inserted = handle.createQuery(
                    """
                    INSERT INTO code_lists (slug, tenant_key, catalog_id, version_id, display_name, description,
                                            source_type, source_url, auth_type, credential,
                                            created_at, updated_at)
                    VALUES (:slug, :tenantKey, ${catalogAt("tenantKey", "catalogKey")}, ${versionAt("tenantKey", "catalogKey")}, :displayName, :description,
                            :sourceType, :sourceUrl, :authType, :credential,
                            NOW(), NOW())
                    RETURNING slug, tenant_key, (SELECT cat.catalog_key FROM catalogs cat
                                WHERE cat.tenant_key = code_lists.tenant_key AND cat.catalog_id = code_lists.catalog_id) AS catalog_key,
                              display_name, description,
                              source_type, source_url, auth_type, credential,
                              last_refreshed_at, last_refresh_error,
                              created_at, updated_at
                    """,
                )
                    .bind("slug", command.id.key)
                    .bind("tenantKey", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("displayName", command.displayName)
                    .bind("description", command.description)
                    .bind("sourceType", command.sourceType.name)
                    .bind("sourceUrl", command.sourceUrl)
                    .bind("authType", command.authType.name)
                    .bind("credential", command.credential?.let(::Secret))
                    .mapTo<CodeList>()
                    .one()

                if (command.entries.isNotEmpty()) {
                    insertEntries(handle, command.id, command.entries)
                }
                inserted
            }
        }
    }
}
