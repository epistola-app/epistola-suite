// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.GenerationCancelReason
import app.epistola.api.model.InteractiveGenerationDto
import app.epistola.api.model.InteractiveGenerationRequest
import app.epistola.api.model.InteractiveGenerationTrigger
import app.epistola.api.model.MissingDataField
import app.epistola.suite.documents.interactive.InteractiveGeneration
import app.epistola.suite.documents.interactive.InteractiveLink
import app.epistola.suite.documents.interactive.InteractiveState
import app.epistola.suite.documents.interactive.InteractiveTrigger
import app.epistola.suite.documents.model.CancelReason
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

// Interactive generation (contract ADR 0011, alpha) between the contract's DTOs and the domain.

internal fun InteractiveGenerationRequest.toDomain() = InteractiveGeneration(
    trigger = when (trigger) {
        InteractiveGenerationTrigger.INCOMPLETE -> InteractiveTrigger.INCOMPLETE
        InteractiveGenerationTrigger.ALWAYS -> InteractiveTrigger.ALWAYS
    },
    expiresInMinutes = expiresInMinutes,
    returnUrl = returnUrl,
    title = title,
    instructions = instructions,
    links = links.orEmpty().map { InteractiveLink(it.label, it.url) },
    locale = locale,
)

/** The echo: present exactly when the server honoured the request's `interactive`. */
internal fun InteractiveState.toDto(objectMapper: ObjectMapper) = InteractiveGenerationDto(
    trigger = when (trigger) {
        InteractiveTrigger.INCOMPLETE -> InteractiveGenerationTrigger.INCOMPLETE
        InteractiveTrigger.ALWAYS -> InteractiveGenerationTrigger.ALWAYS
    },
    missingFields = missingFields.map { MissingDataField(path = it.path, required = it.required, schema = objectMapper.convertValue(it.schema, SCHEMA_MAP)) },
    url = url,
    expiresAt = expiresAt,
)

internal fun CancelReason.toDto(): GenerationCancelReason = when (this) {
    CancelReason.CALLER -> GenerationCancelReason.CALLER
    CancelReason.USER -> GenerationCancelReason.USER
    CancelReason.EXPIRED -> GenerationCancelReason.EXPIRED
}

private val SCHEMA_MAP = object : TypeReference<Map<String, Any>>() {}
