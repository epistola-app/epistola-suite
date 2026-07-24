// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.ContractsApi
import app.epistola.api.model.ContractBreakingChangeDto
import app.epistola.api.model.ContractVersionListResponse
import app.epistola.api.model.ContractVersionSummaryDto
import app.epistola.api.model.CreateContractDraftResponse
import app.epistola.api.model.IncompatibleTemplateVersionDto
import app.epistola.api.model.PublishContractRequest
import app.epistola.api.model.PublishContractResponse
import app.epistola.api.model.UpdateContractDraftRequest
import app.epistola.api.model.UpdateContractDraftResponse
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.TemplateNotFoundException
import app.epistola.suite.templates.contracts.SchemaCompatibilityChecker
import app.epistola.suite.templates.contracts.commands.CreateContractVersion
import app.epistola.suite.templates.contracts.commands.PublishContractVersion
import app.epistola.suite.templates.contracts.commands.UpdateContractVersion
import app.epistola.suite.templates.contracts.model.ContractVersionStatus
import app.epistola.suite.templates.contracts.model.ContractVersionSummary
import app.epistola.suite.templates.contracts.queries.ListContractVersions
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.validation.ValidationException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class EpistolaContractsApi : ContractsApi {

    override fun createContractDraft(
        tenantId: String,
        catalogId: String,
        templateId: String,
    ): ResponseEntity<CreateContractDraftResponse> {
        val id = templateId(tenantId, catalogId, templateId)
        val draft = CreateContractVersion(templateId = id).execute()
            ?: throw TemplateNotFoundException(id.tenantKey, id.key)
        return ResponseEntity.ok(
            CreateContractDraftResponse(
                id = draft.id.value,
                status = CreateContractDraftResponse.Status.DRAFT,
            ),
        )
    }

    override fun updateContractDraft(
        tenantId: String,
        catalogId: String,
        templateId: String,
        updateContractDraftRequest: UpdateContractDraftRequest,
    ): ResponseEntity<UpdateContractDraftResponse> {
        val id = templateId(tenantId, catalogId, templateId)
        val examples = updateContractDraftRequest.dataExamples?.map {
            DataExample(id = it.id, name = it.name, data = it.data)
        }
        val result = UpdateContractVersion(
            templateId = id,
            dataModel = updateContractDraftRequest.dataModel,
            dataExamples = examples,
            forceUpdate = updateContractDraftRequest.forceUpdate ?: false,
        ).execute() ?: throw ValidationException(field = "contract", message = "No draft contract exists")

        return ResponseEntity.ok(
            UpdateContractDraftResponse(
                success = true,
                id = result.contractVersion.id.value,
                status = UpdateContractDraftResponse.Status.DRAFT,
                warnings = result.warnings.values.flatten(),
            ),
        )
    }

    override fun publishContractDraft(
        tenantId: String,
        catalogId: String,
        templateId: String,
        publishContractRequest: PublishContractRequest?,
    ): ResponseEntity<PublishContractResponse> {
        val id = templateId(tenantId, catalogId, templateId)
        val result = PublishContractVersion(
            templateId = id,
            confirmed = publishContractRequest?.confirmed ?: false,
        ).execute() ?: throw ValidationException(field = "contract", message = "No draft contract exists")

        return ResponseEntity.ok(
            PublishContractResponse(
                published = result.published,
                compatible = result.compatible,
                breakingChanges = result.breakingChanges.map { it.toDto() },
                incompatibleVersions = result.incompatibleVersions.map {
                    IncompatibleTemplateVersionDto(
                        variantKey = it.variantKey.value,
                        versionId = it.versionId.value,
                        activeEnvironments = it.activeEnvironments,
                    )
                },
            ),
        )
    }

    override fun listContractVersions(
        tenantId: String,
        catalogId: String,
        templateId: String,
    ): ResponseEntity<ContractVersionListResponse> {
        val id = templateId(tenantId, catalogId, templateId)
        return ResponseEntity.ok(
            ContractVersionListResponse(
                items = ListContractVersions(templateId = id).query().map { it.toDto() },
            ),
        )
    }

    private fun templateId(tenantId: String, catalogId: String, templateId: String): TemplateId {
        val tenantIdComposite = TenantId(TenantKey.of(tenantId))
        return TemplateId(TemplateKey.of(templateId), CatalogId(CatalogKey.of(catalogId), tenantIdComposite))
    }

    private fun ContractVersionSummary.toDto() = ContractVersionSummaryDto(
        id = id.value,
        status = when (status) {
            ContractVersionStatus.DRAFT -> ContractVersionSummaryDto.Status.DRAFT
            ContractVersionStatus.PUBLISHED -> ContractVersionSummaryDto.Status.PUBLISHED
        },
        createdAt = createdAt,
        publishedAt = publishedAt,
    )

    private fun SchemaCompatibilityChecker.BreakingChange.toDto() = ContractBreakingChangeDto(
        type = ContractBreakingChangeDto.Type.valueOf(type.name),
        path = path,
        description = description,
    )
}
