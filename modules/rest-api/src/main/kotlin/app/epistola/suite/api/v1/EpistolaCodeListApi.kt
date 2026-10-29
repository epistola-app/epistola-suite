// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.CodeListsApi
import app.epistola.api.model.CodeListAuthType
import app.epistola.api.model.CodeListDto
import app.epistola.api.model.CodeListEntriesResponse
import app.epistola.api.model.CodeListEntryDto
import app.epistola.api.model.CodeListListResponse
import app.epistola.api.model.CodeListSourceType
import app.epistola.api.model.CreateCodeListRequest
import app.epistola.api.model.UpdateCodeListEntryHiddenRequest
import app.epistola.api.model.UpdateCodeListRequest
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.created
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.readOnlyHere
import app.epistola.suite.api.v1.shared.readRef
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.requestField
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toModel
import app.epistola.suite.attributes.codelists.CodeListNotFoundException
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.DeleteCodeList
import app.epistola.suite.attributes.codelists.commands.RefreshCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeListEntryHidden
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntryPage
import app.epistola.suite.attributes.codelists.queries.ListCodeListPage
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Code lists of a catalog version: a draft reads and changes the working copy, a release reads what it holds. */
@RestController
@RequestMapping("/api")
class EpistolaCodeListApi : CodeListsApi {

    override fun listCodeLists(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        page: Int,
        size: Int,
    ): ResponseEntity<CodeListListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        // A draft or a release, read the same way (ADR 0033); a release is read-only.
        val readOnly = !at.isDraft || at.catalog.readOnlyHere()
        val codeLists = ListCodeListPage(tenantId = TenantId(at.catalog.tenantKey), catalog = at.catalogId, catalogVersion = at.readRef, page = Pagination.requestOf(page, size)).query()
        return ResponseEntity.ok(CodeListListResponse(items = codeLists.items.map { it.toDto(at.catalog, readOnly) }, page = Pagination.pageMeta(page, size, codeLists.total)))
    }

    override fun createCodeList(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        createCodeListRequest: CreateCodeListRequest,
    ): ResponseEntity<CodeListDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val codeList = requestField("displayName", "name") {
            CreateCodeList(
                id = CodeListId(CodeListKey.of(createCodeListRequest.key), draft.catalogId),
                displayName = createCodeListRequest.name,
                description = createCodeListRequest.description,
                sourceType = when (createCodeListRequest.sourceType) {
                    CodeListSourceType.INLINE -> CodeListSource.INLINE
                    CodeListSourceType.URL -> CodeListSource.URL
                },
                sourceUrl = createCodeListRequest.sourceUrl,
                authType = createCodeListRequest.authType?.toModel() ?: AuthType.NONE,
                credential = createCodeListRequest.credential,
                entries = createCodeListRequest.propertyEntries?.map { it.toModel() } ?: emptyList(),
                catalogVersion = draft.ref,
            ).execute()
        }
        return codeList.toDto(draft.catalog).let { created(it.key, it) }
    }

    override fun getCodeList(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        codeListKey: String,
    ): ResponseEntity<CodeListDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val codeList = GetCodeList(id = at.codeListId(codeListKey), catalogVersion = at.readRef).query() ?: throw at.codeListNotFound(codeListKey)
        return okWithETag(codeList.revision, codeList.toDto(at.catalog, readOnly = !at.isDraft || at.catalog.readOnlyHere()))
    }

    override fun updateCodeList(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        codeListKey: String,
        updateCodeListRequest: UpdateCodeListRequest,
        ifMatch: String?,
    ): ResponseEntity<CodeListDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.codeListId(codeListKey)
        // PATCH semantics — fetch current, merge with the request body, then
        // replay through the full UpdateCodeList command (it isn't partial).
        val current = GetCodeList(id = id, catalogVersion = draft.ref).query() ?: throw draft.codeListNotFound(codeListKey)
        val updated = requestField("displayName", "name") {
            UpdateCodeList(
                id = id,
                displayName = updateCodeListRequest.name ?: current.displayName,
                description = updateCodeListRequest.description ?: current.description,
                sourceUrl = updateCodeListRequest.sourceUrl ?: current.sourceUrl,
                authType = updateCodeListRequest.authType?.toModel() ?: current.authType,
                credential = updateCodeListRequest.credential ?: current.credential?.value,
                entries = updateCodeListRequest.propertyEntries?.map { it.toModel() },
                catalogVersion = draft.ref,
                // The merge above read the list at `current`; a write in between must not be lost.
                expectedRevision = ETags.expected(ifMatch) ?: current.revision,
            ).execute() ?: throw draft.codeListNotFound(codeListKey)
        }
        return okWithETag(updated.revision, updated.toDto(draft.catalog))
    }

    override fun deleteCodeList(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        codeListKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        if (!DeleteCodeList(id = draft.codeListId(codeListKey), catalogVersion = draft.ref).execute()) throw draft.codeListNotFound(codeListKey)
        return ResponseEntity.noContent().build()
    }

    /** Refreshing changes the draft's entries, so it is a write. */
    override fun refreshCodeList(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        codeListKey: String,
    ): ResponseEntity<CodeListDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val refreshed = RefreshCodeList(id = draft.codeListId(codeListKey), catalogVersion = draft.ref).execute() ?: throw draft.codeListNotFound(codeListKey)
        return ResponseEntity.ok(refreshed.toDto(draft.catalog))
    }

    override fun listCodeListEntries(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        codeListKey: String,
        page: Int,
        size: Int,
        includeHidden: Boolean,
    ): ResponseEntity<CodeListEntriesResponse> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, release)
        val id = at.codeListId(codeListKey)
        GetCodeList(id = id, catalogVersion = at.readRef).query() ?: throw at.codeListNotFound(codeListKey)
        // Paged in the database: a list holds up to 10000 entries.
        val entries = ListCodeListEntryPage(
            codeListId = id,
            includeHidden = includeHidden,
            page = PageRequest(limit = Pagination.limitOf(size), offset = Pagination.offsetOf(page, size)),
            catalogVersion = at.readRef,
        ).query()
        return ResponseEntity.ok(CodeListEntriesResponse(items = entries.items.map { it.toDto() }, page = Pagination.pageMeta(page, size, entries.total)))
    }

    override fun updateCodeListEntryHidden(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        codeListKey: String,
        code: String,
        updateCodeListEntryHiddenRequest: UpdateCodeListEntryHiddenRequest,
        ifMatch: String?,
    ): ResponseEntity<CodeListEntryDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, release)
        val id = draft.codeListId(codeListKey)
        GetCodeList(id = id, catalogVersion = draft.ref).query() ?: throw draft.codeListNotFound(codeListKey)
        val updated = UpdateCodeListEntryHidden(
            codeListId = id,
            code = code,
            hidden = updateCodeListEntryHiddenRequest.hidden,
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute()
        if (!updated) {
            throw ValidationException(field = "code", message = "Code list entry '$code' not found")
        }
        val entry = ListCodeListEntries(codeListId = id, includeHidden = true, catalogVersion = draft.ref).query()
            .first { it.code == code }
        // The code list's ETag: an entry is part of its list (contract `updateCodeListEntryHidden`).
        val list = requireNotNull(GetCodeList(id = id, catalogVersion = draft.ref).query())
        return okWithETag(list.revision, entry.toDto())
    }

    private fun ResolvedCatalogRelease.codeListId(key: String) = CodeListId(key = CodeListKey.of(key), catalogId = catalogId)

    private fun ResolvedCatalogRelease.codeListNotFound(key: String) = CodeListNotFoundException(catalogId, CodeListKey.of(key))

    private fun CodeListAuthType.toModel(): AuthType = when (this) {
        CodeListAuthType.NONE -> AuthType.NONE
        CodeListAuthType.API_KEY -> AuthType.API_KEY
        CodeListAuthType.BEARER -> AuthType.BEARER
    }
}
