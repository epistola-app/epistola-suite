// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.StencilsApi
import app.epistola.api.model.CreateStencilRequest
import app.epistola.api.model.MarkStencilReadyResponse
import app.epistola.api.model.ResourceStatus
import app.epistola.api.model.StencilContentDto
import app.epistola.api.model.StencilDto
import app.epistola.api.model.StencilListResponse
import app.epistola.api.model.StencilUsageListResponse
import app.epistola.api.model.UpdateStencilContentRequest
import app.epistola.api.model.UpdateStencilRequest
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.WorkingCopyStatus
import app.epistola.suite.api.v1.shared.toContentDto
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.api.v1.shared.toUsageDto
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.StencilNotFoundException
import app.epistola.suite.stencils.StencilVersionNotFoundException
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilInstances
import app.epistola.suite.stencils.queries.ListStencilSummaries
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class EpistolaStencilApi : StencilsApi {

    // ==================== Stencil CRUD ====================

    override fun listStencils(
        tenantId: String,
        catalogId: String,
        q: String?,
        tag: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<StencilListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencils = ListStencilSummaries(
            tenantId = tenantIdComposite,
            searchTerm = q,
            tag = tag,
            catalogKey = CatalogKey.of(catalogId),
        ).query()
        val slice = Pagination.paginate(stencils, page, size)
        val status = WorkingCopyStatus.of(tenantIdComposite.key, CatalogKey.of(catalogId))

        return ResponseEntity.ok(
            StencilListResponse(
                items = slice.items.map { summary ->
                    summary.toSummaryDto(
                        status.of(
                            "stencil",
                            summary.id.value,
                            hasDraft = summary.latestVersion != null && summary.latestVersion != summary.latestPublishedVersion,
                            hasPublished = summary.latestPublishedVersion != null,
                        ),
                    )
                },
                page = slice.page,
            ),
        )
    }

    override fun createStencil(
        tenantId: String,
        catalogId: String,
        createStencilRequest: CreateStencilRequest,
    ): ResponseEntity<StencilDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilId = StencilId(StencilKey.of(createStencilRequest.id), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))

        val stencil = CreateStencil(
            id = stencilId,
            name = createStencilRequest.name,
            description = createStencilRequest.description,
            tags = createStencilRequest.tags ?: emptyList(),
            content = createStencilRequest.content,
            parameterSchema = createStencilRequest.parameterSchema,
        ).execute()

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(stencil.toDto(statusOf(stencilId)))
    }

    override fun getStencil(
        tenantId: String,
        catalogId: String,
        stencilId: String,
    ): ResponseEntity<StencilDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))

        val stencil = GetStencil(id = stencilIdComposite).query()
            ?: throw StencilNotFoundException(tenantIdComposite.key, stencilIdComposite.key)

        return ResponseEntity.ok(stencil.toDto(statusOf(stencilIdComposite)))
    }

    override fun updateStencil(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        updateStencilRequest: UpdateStencilRequest,
    ): ResponseEntity<StencilDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))

        val stencil = UpdateStencil(
            id = stencilIdComposite,
            name = updateStencilRequest.name,
            description = updateStencilRequest.description,
            tags = updateStencilRequest.tags,
        ).execute() ?: throw StencilNotFoundException(tenantIdComposite.key, stencilIdComposite.key)

        return ResponseEntity.ok(stencil.toDto(statusOf(stencilIdComposite)))
    }

    override fun deleteStencil(
        tenantId: String,
        catalogId: String,
        stencilId: String,
    ): ResponseEntity<Unit> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val deleted = DeleteStencil(id = stencilIdComposite).execute()

        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw StencilNotFoundException(tenantIdComposite.key, stencilIdComposite.key)
        }
    }

    // ==================== Working copy ====================
    //
    // Interim, until stencils lose their versions (WP4): the working copy is the stencil's draft
    // version when it has one, otherwise its latest published version, and marking it ready publishes
    // the draft, which is what a release reads.

    override fun getStencilContent(
        tenantId: String,
        catalogId: String,
        stencilId: String,
    ): ResponseEntity<StencilContentDto> {
        val id = stencilIdOf(tenantId, catalogId, stencilId)
        val (version, status) = workingCopy(id)
        return ResponseEntity.ok(version.toContentDto(status))
    }

    override fun updateStencilContent(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        updateStencilContentRequest: UpdateStencilContentRequest,
    ): ResponseEntity<StencilContentDto> {
        val id = stencilIdOf(tenantId, catalogId, stencilId)
        val content = updateStencilContentRequest.content
            ?: throw ValidationException(field = "content", message = "Stencil content is required")
        val draftKey = draftOf(id)?.id
            ?: (CreateStencilVersion(stencilId = id, inheritParameterSchemaFromSource = false).execute()?.id)
            ?: throw StencilNotFoundException(id.tenantKey, id.key)
        UpdateStencilDraft(
            versionId = StencilVersionId(draftKey, id),
            content = content,
            parameterSchema = updateStencilContentRequest.parameterSchema,
        ).execute()
        val (version, status) = workingCopy(id)
        return ResponseEntity.ok(version.toContentDto(status))
    }

    override fun markStencilReady(
        tenantId: String,
        catalogId: String,
        stencilId: String,
    ): ResponseEntity<MarkStencilReadyResponse> {
        val id = stencilIdOf(tenantId, catalogId, stencilId)
        draftOf(id)?.let { PublishStencilVersion(versionId = StencilVersionId(it.id, id)).execute() }
        val (version, status) = workingCopy(id)
        val heldBack = generateSequence(0) { it + HELD_BACK_PAGE }
            .map { offset -> ListStencilInstances(id, offset = offset, limit = HELD_BACK_PAGE).query() }
            .takeWhile { it.items.isNotEmpty() }
            .flatMap { page -> page.items.asSequence() }
            .filter { it.heldBack }
            .map { it.toUsageDto() }
            .toList()
        return ResponseEntity.ok(MarkStencilReadyResponse(stencil = version.toContentDto(status), heldBack = heldBack))
    }

    // ==================== Usage ====================

    override fun listStencilUsage(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        page: Int,
        size: Int,
    ): ResponseEntity<StencilUsageListResponse> {
        val id = stencilIdOf(tenantId, catalogId, stencilId)
        GetStencil(id = id).query() ?: throw StencilNotFoundException(id.tenantKey, id.key)
        val instances = ListStencilInstances(id, offset = Pagination.offsetOf(page, size), limit = Pagination.limitOf(size)).query()
        return ResponseEntity.ok(
            StencilUsageListResponse(
                items = instances.items.map { it.toUsageDto() },
                page = Pagination.pageMeta(page, size, instances.total),
            ),
        )
    }

    // ==================== Helpers ====================

    private fun stencilIdOf(tenantId: String, catalogId: String, stencilId: String) = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), TenantId(TenantKey.of(tenantId))))

    private fun statusOf(id: StencilId): ResourceStatus = workingCopy(id).second

    private fun draftOf(id: StencilId) = ListStencilVersions(stencilId = id, status = StencilVersionStatus.DRAFT).query().firstOrNull()

    /** The working copy and its status: the draft if there is one, else the latest published version. */
    private fun workingCopy(id: StencilId): Pair<StencilVersion, ResourceStatus> {
        GetStencil(id = id).query() ?: throw StencilNotFoundException(id.tenantKey, id.key)
        val draft = draftOf(id)
        val latestPublished = ListStencilVersions(stencilId = id, status = StencilVersionStatus.PUBLISHED).query().maxByOrNull { it.id.value }
        val shownKey = draft?.id ?: latestPublished?.id ?: throw StencilNotFoundException(id.tenantKey, id.key)
        val version = GetStencilVersion(versionId = StencilVersionId(shownKey, id)).query()
            ?: throw StencilNotFoundException(id.tenantKey, id.key)
        val status = WorkingCopyStatus.of(id.tenantKey, id.catalogKey)
            .of("stencil", id.key.value, hasDraft = draft != null, hasPublished = latestPublished != null)
        return version to status
    }

    private companion object {
        const val HELD_BACK_PAGE = 200
    }
}
