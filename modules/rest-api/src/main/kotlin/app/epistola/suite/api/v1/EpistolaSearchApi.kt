// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.SearchApi
import app.epistola.api.model.SearchResults
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Searching a tenant by name (`searchTenant`, contract 2.0.0-alpha.6, alpha), the REST side of
 * ADR 0043. Not built yet: the route answers `501 operation-not-implemented`, so a client can tell
 * "not here yet" from "no such address".
 */
@RestController
@RequestMapping("/api")
class EpistolaSearchApi : SearchApi {

    override fun searchTenant(
        tenantKey: String,
        q: String?,
        kind: String?,
        registry: String?,
        namespace: String?,
        catalogKey: String?,
        supplied: Boolean?,
        changed: Boolean?,
        sort: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<SearchResults> = throw ApiOperationNotImplementedException("searchTenant")
}
