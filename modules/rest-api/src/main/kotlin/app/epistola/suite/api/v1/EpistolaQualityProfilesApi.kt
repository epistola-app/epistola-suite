// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.QualityProfilesApi
import app.epistola.api.model.CreateQualityProfileRequest
import app.epistola.api.model.QualityProfileDto
import app.epistola.api.model.QualityProfileListResponse
import app.epistola.api.model.UpdateQualityProfileRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Quality profiles (contract 2.0.0-alpha.6, `x-experimental`; contract ADR 0010, suite ADR 0046).
 *
 * The suite stores no quality profiles yet. The contract asks a server that does not support them
 * to answer every operation `501 operation-not-implemented` rather than leave the route unmapped, so
 * a client can tell "not here yet" from "no such address". A `quality` binding in a template or
 * release request is refused the same way ([refuseQualityBinding]).
 */
@RestController
@RequestMapping("/api")
class EpistolaQualityProfilesApi : QualityProfilesApi {

    override fun listQualityProfiles(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        q: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<QualityProfileListResponse> = throw ApiOperationNotImplementedException("listQualityProfiles")

    override fun createQualityProfile(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        createQualityProfileRequest: CreateQualityProfileRequest,
    ): ResponseEntity<QualityProfileDto> = throw ApiOperationNotImplementedException("createQualityProfile")

    override fun getQualityProfile(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        profileKey: String,
    ): ResponseEntity<QualityProfileDto> = throw ApiOperationNotImplementedException("getQualityProfile")

    override fun updateQualityProfile(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        profileKey: String,
        updateQualityProfileRequest: UpdateQualityProfileRequest,
        ifMatch: String?,
    ): ResponseEntity<QualityProfileDto> = throw ApiOperationNotImplementedException("updateQualityProfile")

    override fun deleteQualityProfile(
        tenantKey: String,
        registry: String,
        namespace: String,
        catalogKey: String,
        release: String,
        profileKey: String,
    ): ResponseEntity<Unit> = throw ApiOperationNotImplementedException("deleteQualityProfile")
}

/**
 * Refuses a `quality` binding in a request (`createTemplate`, `updateTemplate`,
 * `updateCatalogRelease`) with `501 operation-not-implemented`, as the contract asks of a server
 * without quality profiles, rather than accepting the request and dropping the binding.
 */
internal fun refuseQualityBinding(operation: String, quality: Any?) {
    if (quality != null) throw ApiOperationNotImplementedException("$operation with a quality binding")
}
