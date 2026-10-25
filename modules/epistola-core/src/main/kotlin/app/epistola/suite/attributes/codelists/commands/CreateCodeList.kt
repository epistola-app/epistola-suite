// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CodeListFields
import app.epistola.suite.attributes.codelists.CodeListSourceSettings
import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
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
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
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
    private val codeLists: CodeListStore,
) : CommandHandler<CreateCodeList, CodeList> {
    override fun handle(command: CreateCodeList): CodeList {
        requireCatalogEditable(command.id.catalogId)
        if (command.sourceType != CodeListSource.INLINE) {
            command.sourceUrl?.let { codeListClient.requireAllowedSourceUrl(it) }
        }
        return executeOrThrowDuplicate("code-list", command.id.key.value) {
            jdbi.withHandle<CodeList, Exception> { handle ->
                val source = if (command.sourceType == CodeListSource.INLINE) {
                    null
                } else {
                    CodeListSourceSettings(command.sourceType, command.sourceUrl, command.authType, command.credential?.let { codeLists.storeCredential(handle, command.id.catalogId, it) })
                }
                codeLists.create(handle, command.id, CodeListFields(command.displayName, command.description, command.entries, source), currentUserIdOrNull()?.value)
            }
        }
    }
}
