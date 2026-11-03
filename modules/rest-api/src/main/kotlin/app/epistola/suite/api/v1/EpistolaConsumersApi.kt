// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.ConsumersApi
import app.epistola.api.model.ApproveConsumerRequest
import app.epistola.api.model.ConsumerDto
import app.epistola.api.model.ConsumerListResponse
import app.epistola.api.model.RegisterConsumerRequest
import app.epistola.api.model.RejectConsumerRequest
import app.epistola.api.model.RotatePublicKeyRequest
import app.epistola.api.model.UpdateConsumerRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The Consumers API (the consumer model, `x-experimental`): the suite authenticates API keys and
 * Bearer JWTs, but keeps no registry of consumers yet. The contract asks a server that does not
 * implement an operation to answer it `501 operation-not-implemented`, not leave the route
 * unmapped, so a client can tell "not here yet" from "no such address".
 */
@RestController
@RequestMapping("/api")
class EpistolaConsumersApi : ConsumersApi {

    override fun listConsumers(tenantKey: String, q: String?, status: String?, page: Int, size: Int): ResponseEntity<ConsumerListResponse> = throw ApiOperationNotImplementedException("listConsumers")

    override fun registerConsumer(tenantKey: String, registerConsumerRequest: RegisterConsumerRequest): ResponseEntity<ConsumerDto> = throw ApiOperationNotImplementedException("registerConsumer")

    override fun getConsumer(tenantKey: String, consumerId: String): ResponseEntity<ConsumerDto> = throw ApiOperationNotImplementedException("getConsumer")

    override fun updateConsumer(
        tenantKey: String,
        consumerId: String,
        updateConsumerRequest: UpdateConsumerRequest,
        ifMatch: String?,
    ): ResponseEntity<ConsumerDto> = throw ApiOperationNotImplementedException("updateConsumer")

    override fun deleteConsumer(tenantKey: String, consumerId: String): ResponseEntity<Unit> = throw ApiOperationNotImplementedException("deleteConsumer")

    override fun approveConsumer(tenantKey: String, consumerId: String, approveConsumerRequest: ApproveConsumerRequest): ResponseEntity<ConsumerDto> = throw ApiOperationNotImplementedException("approveConsumer")

    override fun rejectConsumer(tenantKey: String, consumerId: String, rejectConsumerRequest: RejectConsumerRequest?): ResponseEntity<ConsumerDto> = throw ApiOperationNotImplementedException("rejectConsumer")

    override fun rotatePublicKey(tenantKey: String, consumerId: String, rotatePublicKeyRequest: RotatePublicKeyRequest): ResponseEntity<ConsumerDto> = throw ApiOperationNotImplementedException("rotatePublicKey")
}
