// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.StencilsApi
import app.epistola.api.model.CreateStencilRequest
import app.epistola.api.model.MarkStencilReadyResponse
import app.epistola.api.model.StencilContentDto
import app.epistola.api.model.StencilDto
import app.epistola.api.model.StencilListResponse
import app.epistola.api.model.StencilUsageListResponse
import app.epistola.api.model.UpdateStencilContentRequest
import app.epistola.api.model.UpdateStencilRequest
import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.api.v1.shared.ETags
import app.epistola.suite.api.v1.shared.ListSorting
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.ResourceStatuses
import app.epistola.suite.api.v1.shared.catalogId
import app.epistola.suite.api.v1.shared.catalogVersionAt
import app.epistola.suite.api.v1.shared.created
import app.epistola.suite.api.v1.shared.draftAt
import app.epistola.suite.api.v1.shared.okWithETag
import app.epistola.suite.api.v1.shared.ref
import app.epistola.suite.api.v1.shared.releasedResource
import app.epistola.suite.api.v1.shared.releasedResources
import app.epistola.suite.api.v1.shared.toContentDto
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.api.v1.shared.toUsageDto
import app.epistola.suite.catalog.versions.ResolvedCatalogVersion
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.StencilNotFoundException
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.MarkStencilReady
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.CountStencils
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.validation.ValidationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

/**
 * Stencils of a catalog version. A stencil has one working copy, in the draft; its status is against
 * the version's base and its ready mark (contract ADR 0002). A released version reads the stencils
 * the release holds.
 */
@RestController
@RequestMapping("/api")
class EpistolaStencilApi(
    private val objectMapper: ObjectMapper,
) : StencilsApi {

    // ==================== Stencil CRUD ====================

    override fun listStencils(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        q: String?,
        tag: String?,
        page: Int,
        size: Int,
        sort: String?,
        direction: String,
    ): ResponseEntity<StencilListResponse> {
        // This endpoint has no sortable columns; reject a caller-supplied sort rather than ignore it.
        ListSorting.rejectUnsupportedSort(sort, direction)
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!at.isDraft) {
            // Released content is paged in memory: it is assembled whole, and bounded by the catalog.
            val stencils = at.releasedResources<StencilResource>("stencil").filter { stencil ->
                (q.isNullOrBlank() || stencil.name.contains(q, ignoreCase = true) || stencil.description?.contains(q, ignoreCase = true) == true) &&
                    (tag.isNullOrBlank() || tag in stencil.tags)
            }
            val slice = Pagination.pageOfRelease(stencils, page, size)
            return ResponseEntity.ok(StencilListResponse(items = slice.items.map { it.toSummaryDto(at) }, page = slice.page))
        }
        val tenantId = TenantId(at.catalog.tenantKey)
        val stencils = ListStencils(
            tenantId = tenantId,
            searchTerm = q,
            tag = tag,
            catalogKey = at.catalogKey,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
            catalogVersion = at.ref,
        ).query()
        val total = CountStencils(tenantId = tenantId, searchTerm = q, tag = tag, catalogKey = at.catalogKey, catalogVersion = at.ref).query()
        val statuses = ResourceStatuses.of(at)
        return ResponseEntity.ok(
            StencilListResponse(
                items = stencils.map { it.toSummaryDto(statuses.of(STENCIL, it.id.value)) },
                page = Pagination.pageMeta(page, size, total),
            ),
        )
    }

    override fun createStencil(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        createStencilRequest: CreateStencilRequest,
    ): ResponseEntity<StencilDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val stencil = CreateStencil(
            id = StencilId(StencilKey.of(createStencilRequest.key), draft.catalogId),
            name = createStencilRequest.name,
            description = createStencilRequest.description,
            tags = createStencilRequest.tags ?: emptyList(),
            content = createStencilRequest.content,
            parameterSchema = createStencilRequest.parameterSchema,
            catalogVersion = draft.ref,
        ).execute()

        return stencil.toDto(ResourceStatuses.of(draft).of(STENCIL, stencil.id.value)).let { created(it.key, it) }
    }

    override fun getStencil(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
    ): ResponseEntity<StencilDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!at.isDraft) return ResponseEntity.ok(released(at, stencilKey).toDto(at))
        val stencil = workingCopy(at, stencilKey)
        return okWithETag(stencil.revision, stencil.toDto(ResourceStatuses.of(at).of(STENCIL, stencil.id.value)))
    }

    override fun updateStencil(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
        updateStencilRequest: UpdateStencilRequest,
        ifMatch: String?,
    ): ResponseEntity<StencilDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.stencilId(stencilKey)
        val stencil = UpdateStencil(
            id = id,
            name = updateStencilRequest.name,
            description = updateStencilRequest.description,
            tags = updateStencilRequest.tags,
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute() ?: throw StencilNotFoundException(id.tenantKey, id.key)

        return okWithETag(stencil.revision, stencil.toDto(ResourceStatuses.of(draft).of(STENCIL, stencil.id.value)))
    }

    override fun deleteStencil(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
    ): ResponseEntity<Unit> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.stencilId(stencilKey)
        if (!DeleteStencil(id = id, catalogVersion = draft.ref).execute()) throw StencilNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.noContent().build()
    }

    // ==================== Content ====================

    override fun getStencilContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
    ): ResponseEntity<StencilContentDto> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        if (!at.isDraft) return ResponseEntity.ok(released(at, stencilKey).toContentDto(at, objectMapper))
        val stencil = workingCopy(at, stencilKey)
        return okWithETag(stencil.revision, stencil.toContentDto(ResourceStatuses.of(at).of(STENCIL, stencil.id.value)))
    }

    /** A full replacement: a parameter schema left out is cleared, as the contract says. */
    override fun updateStencilContent(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
        updateStencilContentRequest: UpdateStencilContentRequest,
        ifMatch: String?,
    ): ResponseEntity<StencilContentDto> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.stencilId(stencilKey)
        val content = updateStencilContentRequest.content
            ?: throw ValidationException(field = "content", message = "Stencil content is required")
        val parameterSchema = updateStencilContentRequest.parameterSchema
        val stencil = UpdateStencil(
            id = id,
            content = content,
            // Left out keeps the schema; an empty one (`{}`) removes it (UpdateStencil).
            parameterSchema = parameterSchema,
            catalogVersion = draft.ref,
            expectedRevision = ETags.expected(ifMatch),
        ).execute() ?: throw StencilNotFoundException(id.tenantKey, id.key)
        return okWithETag(stencil.revision, stencil.toContentDto(ResourceStatuses.of(draft).of(STENCIL, stencil.id.value)))
    }

    /**
     * Marks the stencil's content ready. Templates use a stencil by reference, so every instance sees
     * the new content; the instances it no longer fits are named, and a release refuses until they do.
     */
    override fun markStencilReady(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
    ): ResponseEntity<MarkStencilReadyResponse> {
        val draft = draftAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        val id = draft.stencilId(stencilKey)
        workingCopy(draft, stencilKey)
        MarkStencilReady(stencilId = id, catalogVersion = draft.ref).execute()
        val stencil = workingCopy(draft, stencilKey)
        val notFitting = generateSequence(1) { it + 1 }
            .map { GetStencilUsagePage(id, page = it, catalogVersion = draft.ref).query() }
            .takeWhileInclusive { it.page < it.totalPages }
            .flatMap { it.items.asSequence() }
            .map { it.toUsageDto() }
            .filterNot { it.fits }
            .toList()
        return ResponseEntity.ok(
            MarkStencilReadyResponse(
                stencil = stencil.toContentDto(ResourceStatuses.of(draft).of(STENCIL, stencil.id.value)),
                notFitting = notFitting,
            ),
        )
    }

    // ==================== Usage ====================

    /** The variants that use the stencil, paged in SQL. */
    override fun listStencilUsage(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        catalogVersion: String,
        stencilKey: String,
        page: Int,
        size: Int,
    ): ResponseEntity<StencilUsageListResponse> {
        val at = catalogVersionAt(tenantKey, registry, namespace, catalogKey, catalogVersion)
        // The usage scan reads the working copy's templates; nothing scans what a release holds yet.
        if (!at.isDraft) throw ApiOperationNotImplementedException("listStencilUsage on a released version")
        val id = at.stencilId(stencilKey)
        workingCopy(at, stencilKey)

        val usage = GetStencilUsagePage(id, page = page.coerceAtLeast(0) + 1, pageSize = Pagination.limitOf(size), catalogVersion = at.ref).query()
        // The usage query answers a page past the end with its last page; the contract with none.
        val inRange = usage.page == page.coerceAtLeast(0) + 1
        return ResponseEntity.ok(
            StencilUsageListResponse(
                items = if (inRange) usage.items.map { it.toUsageDto() } else emptyList(),
                page = Pagination.pageMeta(page, size, usage.total.toLong()),
            ),
        )
    }

    // ==================== Helpers ====================

    private fun ResolvedCatalogVersion.stencilId(key: String) = StencilId(StencilKey.of(key), catalogId)

    private fun workingCopy(at: ResolvedCatalogVersion, key: String): Stencil {
        val id = at.stencilId(key)
        return GetStencil(id = id, catalogVersion = at.ref).query() ?: throw StencilNotFoundException(id.tenantKey, id.key)
    }

    private fun released(at: ResolvedCatalogVersion, key: String): StencilResource = at.releasedResource<StencilResource>(STENCIL, key)
        ?: throw StencilNotFoundException(at.catalog.tenantKey, StencilKey.of(key))

    private fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean): Sequence<T> = sequence {
        for (item in this@takeWhileInclusive) {
            yield(item)
            if (!predicate(item)) break
        }
    }

    private companion object {
        const val STENCIL = "stencil"
    }
}
