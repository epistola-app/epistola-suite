package app.epistola.suite.api.v1

import app.epistola.api.StencilsApi
import app.epistola.api.model.CreateStencilRequest
import app.epistola.api.model.CreateStencilVersionRequest
import app.epistola.api.model.StencilDto
import app.epistola.api.model.StencilListResponse
import app.epistola.api.model.StencilUsageListResponse
import app.epistola.api.model.StencilVersionDto
import app.epistola.api.model.StencilVersionListResponse
import app.epistola.api.model.UpdateStencilDraftRequest
import app.epistola.api.model.UpdateStencilRequest
import app.epistola.api.model.UpgradePreviewListResponse
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toStencilVersionStatus
import app.epistola.suite.api.v1.shared.toSummaryDto
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.ArchiveStencilVersion
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsage
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilSummaries
import app.epistola.suite.stencils.queries.ListStencilVersions
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api")
class EpistolaStencilApi(
    private val objectMapper: ObjectMapper,
) : StencilsApi {

    // ==================== Stencil CRUD ====================

    override fun listStencils(
        tenantId: String,
        catalogId: String,
        q: String?,
        tag: String?,
    ): ResponseEntity<StencilListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencils = ListStencilSummaries(
            tenantId = tenantIdComposite,
            searchTerm = q,
            tag = tag,
        ).query()

        return ResponseEntity.ok(
            StencilListResponse(
                items = stencils.map { it.toSummaryDto() },
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
        val content = createStencilRequest.content?.let {
            objectMapper.treeToValue(objectMapper.valueToTree(it), app.epistola.template.model.TemplateDocument::class.java)
        }

        val stencil = CreateStencil(
            id = stencilId,
            name = createStencilRequest.name,
            description = createStencilRequest.description,
            tags = createStencilRequest.tags ?: emptyList(),
            content = content,
        ).execute()

        val versions = ListStencilVersions(stencilId = stencilId).query()
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(stencil.toDto(versions))
    }

    override fun getStencil(
        tenantId: String,
        catalogId: String,
        stencilId: String,
    ): ResponseEntity<StencilDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))

        val stencil = GetStencil(id = stencilIdComposite).query()
            ?: return ResponseEntity.notFound().build()

        val versions = ListStencilVersions(stencilId = stencilIdComposite).query()
        return ResponseEntity.ok(stencil.toDto(versions))
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
        ).execute() ?: return ResponseEntity.notFound().build()

        val versions = ListStencilVersions(stencilId = stencilIdComposite).query()
        return ResponseEntity.ok(stencil.toDto(versions))
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
            ResponseEntity.notFound().build()
        }
    }

    // ==================== Stencil Versions ====================

    override fun listStencilVersions(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        status: String?,
    ): ResponseEntity<StencilVersionListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))

        val versions = ListStencilVersions(
            stencilId = stencilIdComposite,
            status = status?.toStencilVersionStatus(),
        ).query()

        return ResponseEntity.ok(
            StencilVersionListResponse(
                items = versions.map { it.toDto() },
            ),
        )
    }

    override fun createStencilVersion(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        createStencilVersionRequest: CreateStencilVersionRequest?,
    ): ResponseEntity<StencilVersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val content = createStencilVersionRequest?.content?.let {
            objectMapper.treeToValue(objectMapper.valueToTree(it), app.epistola.template.model.TemplateDocument::class.java)
        }

        val version = CreateStencilVersion(
            stencilId = stencilIdComposite,
            content = content,
        ).execute() ?: return ResponseEntity.notFound().build()

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(version.toDto(objectMapper))
    }

    override fun getStencilVersion(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        versionId: Int,
    ): ResponseEntity<StencilVersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilIdComposite)

        val version = GetStencilVersion(versionId = versionIdComposite).query()
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun updateStencilDraft(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        versionId: Int,
        updateStencilDraftRequest: UpdateStencilDraftRequest,
    ): ResponseEntity<StencilVersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilIdComposite)
        val content = updateStencilDraftRequest.content?.let {
            objectMapper.treeToValue(objectMapper.valueToTree(it), app.epistola.template.model.TemplateDocument::class.java)
        } ?: return ResponseEntity.badRequest().build()

        val version = UpdateStencilDraft(
            versionId = versionIdComposite,
            content = content,
        ).execute() ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    // ==================== Stencil Version Lifecycle ====================

    override fun publishStencilVersion(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        versionId: Int,
    ): ResponseEntity<StencilVersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilIdComposite)

        val version = PublishStencilVersion(versionId = versionIdComposite).execute()
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    override fun archiveStencilVersion(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        versionId: Int,
    ): ResponseEntity<StencilVersionDto> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilIdComposite)

        val version = ArchiveStencilVersion(versionId = versionIdComposite).execute()
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(version.toDto(objectMapper))
    }

    // ==================== Usage & Upgrade ====================

    override fun getStencilVersionUsage(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        versionId: Int,
    ): ResponseEntity<StencilUsageListResponse> {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        val stencilIdComposite = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
        val versionIdComposite = StencilVersionId(VersionKey.of(versionId), stencilIdComposite)

        val usages = GetStencilUsage(versionId = versionIdComposite).query()

        return ResponseEntity.ok(
            StencilUsageListResponse(
                items = usages.map { it.toDto() },
            ),
        )
    }

    override fun previewStencilUpgrade(
        tenantId: String,
        catalogId: String,
        stencilId: String,
        versionId: Int,
    ): ResponseEntity<UpgradePreviewListResponse> {
        // Upgrade preview is a Phase 5 feature — return empty for now
        return ResponseEntity.ok(
            UpgradePreviewListResponse(items = emptyList()),
        )
    }
}
