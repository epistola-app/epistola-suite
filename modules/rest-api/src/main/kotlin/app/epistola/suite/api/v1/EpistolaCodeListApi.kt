package app.epistola.suite.api.v1

import app.epistola.api.CodeListsApi
import app.epistola.api.model.CodeListDto
import app.epistola.api.model.CodeListEntriesResponse
import app.epistola.api.model.CodeListEntryDto
import app.epistola.api.model.CodeListListResponse
import app.epistola.api.model.CreateCodeListRequest
import app.epistola.api.model.UpdateCodeListEntryHiddenRequest
import app.epistola.api.model.UpdateCodeListRequest
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
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
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST surface for tenant + catalog code lists.
 *
 * Maps directly onto the existing mediator commands / queries — no new
 * domain logic. Exception translation:
 *  - `CodeListInUseException` → 409 (an attribute still binds to the list)
 *  - `CatalogReadOnlyException` (SUBSCRIBED catalog) → 409
 *  - `DuplicateIdException` (slug collision) → 409
 *  - `CodeListNotRefreshableException` (refresh on INLINE) → 400
 *  - `ValidationException` → 400 (already mapped globally)
 *
 * Read-only flagging on the DTO (`readOnly: true` when the catalog is
 * SUBSCRIBED) is set in `CodeListDtoMappers.toDto`. The write commands
 * already throw `CatalogReadOnlyException` for SUBSCRIBED catalogs via
 * `requireCatalogEditable`, so we don't double-check here.
 */
@RestController
@RequestMapping("/api")
class EpistolaCodeListApi : CodeListsApi {

    override fun listCodeLists(
        tenantId: String,
        catalogId: String,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<CodeListListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogKey = CatalogKey.of(catalogId)
        val codeLists = ListCodeLists(tenantId = tenantIdComposite, catalogKey = catalogKey).query()
        val slice = Pagination.paginate(codeLists, page, size)
        return ResponseEntity.ok(CodeListListResponse(items = slice.items.map { it.toDto() }, page = slice.page))
    }

    override fun createCodeList(
        tenantId: String,
        catalogId: String,
        createCodeListRequest: CreateCodeListRequest,
    ): ResponseEntity<CodeListDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val catalogIdComposite = CatalogId(CatalogKey.of(catalogId), tenantIdComposite)
        val codeListId = CodeListId(CodeListKey.of(createCodeListRequest.slug), catalogIdComposite)
        val codeList = CreateCodeList(
            id = codeListId,
            displayName = createCodeListRequest.displayName,
            description = createCodeListRequest.description,
            sourceType = when (createCodeListRequest.sourceType) {
                CreateCodeListRequest.SourceType.INLINE -> CodeListSource.INLINE
                CreateCodeListRequest.SourceType.URL -> CodeListSource.URL
            },
            sourceUrl = createCodeListRequest.sourceUrl,
            authType = createCodeListRequest.authType?.toModel() ?: AuthType.NONE,
            credential = createCodeListRequest.credential,
            entries = createCodeListRequest.propertyEntries?.map { it.toModel() } ?: emptyList(),
        ).execute()
        return ResponseEntity.status(HttpStatus.CREATED).body(codeList.toDto())
    }

    override fun getCodeList(
        tenantId: String,
        catalogId: String,
        codeListSlug: String,
    ): ResponseEntity<CodeListDto> {
        val id = buildId(tenantId, catalogId, codeListSlug)
        val codeList = GetCodeList(id = id).query()
            ?: throw CodeListNotFoundException(id.tenantKey, id.catalogKey, id.key)
        return ResponseEntity.ok(codeList.toDto())
    }

    override fun updateCodeList(
        tenantId: String,
        catalogId: String,
        codeListSlug: String,
        updateCodeListRequest: UpdateCodeListRequest,
    ): ResponseEntity<CodeListDto> {
        val id = buildId(tenantId, catalogId, codeListSlug)
        // PATCH semantics — fetch current, merge with the request body, then
        // replay through the full UpdateCodeList command (it isn't partial).
        val current = GetCodeList(id = id).query() ?: throw CodeListNotFoundException(id.tenantKey, id.catalogKey, id.key)
        val updated = UpdateCodeList(
            id = id,
            displayName = updateCodeListRequest.displayName ?: current.displayName,
            description = updateCodeListRequest.description ?: current.description,
            sourceUrl = updateCodeListRequest.sourceUrl ?: current.sourceUrl,
            authType = updateCodeListRequest.authType?.toModel() ?: current.authType,
            credential = updateCodeListRequest.credential ?: current.credential?.value,
            entries = updateCodeListRequest.propertyEntries?.map { it.toModel() },
        ).execute() ?: throw CodeListNotFoundException(id.tenantKey, id.catalogKey, id.key)
        return ResponseEntity.ok(updated.toDto())
    }

    override fun deleteCodeList(
        tenantId: String,
        catalogId: String,
        codeListSlug: String,
    ): ResponseEntity<Unit> {
        val id = buildId(tenantId, catalogId, codeListSlug)
        val deleted = DeleteCodeList(id = id).execute()
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw CodeListNotFoundException(id.tenantKey, id.catalogKey, id.key)
        }
    }

    override fun refreshCodeList(
        tenantId: String,
        catalogId: String,
        codeListSlug: String,
    ): ResponseEntity<CodeListDto> {
        val id = buildId(tenantId, catalogId, codeListSlug)
        val refreshed = RefreshCodeList(id = id).execute() ?: throw CodeListNotFoundException(id.tenantKey, id.catalogKey, id.key)
        return ResponseEntity.ok(refreshed.toDto())
    }

    override fun listCodeListEntries(
        tenantId: String,
        catalogId: String,
        codeListSlug: String,
        includeHidden: Boolean,
    ): ResponseEntity<CodeListEntriesResponse> {
        val id = buildId(tenantId, catalogId, codeListSlug)
        val entries = ListCodeListEntries(codeListId = id, includeHidden = includeHidden).query()
        return ResponseEntity.ok(CodeListEntriesResponse(items = entries.map { it.toDto() }))
    }

    override fun updateCodeListEntryHidden(
        tenantId: String,
        catalogId: String,
        codeListSlug: String,
        code: String,
        updateCodeListEntryHiddenRequest: UpdateCodeListEntryHiddenRequest,
    ): ResponseEntity<CodeListEntryDto> {
        val id = buildId(tenantId, catalogId, codeListSlug)
        GetCodeList(id = id).query() ?: throw CodeListNotFoundException(id.tenantKey, id.catalogKey, id.key)
        val updated = UpdateCodeListEntryHidden(
            codeListId = id,
            code = code,
            hidden = updateCodeListEntryHiddenRequest.hidden,
        ).execute()
        if (!updated) {
            throw ValidationException(field = "code", message = "Code list entry '$code' not found")
        }
        val entry = ListCodeListEntries(codeListId = id, includeHidden = true).query()
            .first { it.code == code }
        return ResponseEntity.ok(entry.toDto())
    }

    private fun buildId(tenantId: String, catalogId: String, codeListSlug: String) = CodeListId(
        key = CodeListKey.of(codeListSlug),
        catalogId = CatalogId(CatalogKey.of(catalogId), TenantId(TenantKey.of(tenantId))),
    )

    private fun CreateCodeListRequest.AuthType.toModel(): AuthType = when (this) {
        CreateCodeListRequest.AuthType.NONE -> AuthType.NONE
        CreateCodeListRequest.AuthType.API_KEY -> AuthType.API_KEY
        CreateCodeListRequest.AuthType.BEARER -> AuthType.BEARER
    }

    private fun UpdateCodeListRequest.AuthType.toModel(): AuthType = when (this) {
        UpdateCodeListRequest.AuthType.NONE -> AuthType.NONE
        UpdateCodeListRequest.AuthType.API_KEY -> AuthType.API_KEY
        UpdateCodeListRequest.AuthType.BEARER -> AuthType.BEARER
    }
}
