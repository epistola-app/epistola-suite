// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.SystemApi
import app.epistola.api.model.PingRequest
import app.epistola.api.model.PongDetailsDto
import app.epistola.api.model.PongResponse
import app.epistola.suite.generation.collect.commands.TouchConsumerNode
import app.epistola.suite.generation.collect.queries.GetServerInfo
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.time.EpistolaClock
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import app.epistola.api.model.PartitionAssignment as WirePartitionAssignment

/**
 * v0.3 `/ping` — health and metadata exchange.
 *
 * Unauthenticated calls get a minimal pong (status + timestamp) — the consumer
 * model has no concept of "anonymous identity" for the partition assignment
 * step. Authenticated calls additionally:
 *   - dispatch [GetServerInfo] for serverVersion / apiVersion / nodeId,
 *   - dispatch [TouchConsumerNode] (when X-EP-Node-Id is supplied) to bump the
 *     consumer node's heartbeat and return its current partition assignment.
 *
 * Thin pass-through to the commands/queries — no business logic in the
 * controller, per the agreed CQRS architecture.
 */
@RestController
@RequestMapping("/api")
class EpistolaSystemApi : SystemApi {

    override fun ping(pingRequest: PingRequest?): ResponseEntity<PongResponse> {
        val now = EpistolaClock.offsetDateTime()
        val principal = SecurityContext.currentOrNull()

        if (principal == null) {
            // Unauthenticated path — health check only.
            return ResponseEntity.ok(
                PongResponse(
                    status = PongResponse.Status.UP,
                    timestamp = now,
                    details = null,
                ),
            )
        }

        val info = GetServerInfo().query()
        val tenantKey = principal.currentTenantId
        val nodeId = currentRequest()?.getHeader(HEADER_NODE_ID)?.takeIf { it.isNotBlank() }
        // Compute the caller's current partition assignment when we have everything
        // needed to do so — tenant + nodeId. Otherwise leave the partition info as
        // an empty list (the consumer hasn't told us its node yet).
        val partitions = if (tenantKey != null && nodeId != null) {
            val assignment = TouchConsumerNode(
                tenantId = tenantKey,
                consumerId = principal.userId.value.toString(),
                nodeId = nodeId,
            ).execute()
            WirePartitionAssignment(
                total = assignment.total,
                mine = assignment.mine,
                hash = WirePartitionAssignment.Hash.MURMUR3,
            )
        } else {
            WirePartitionAssignment(
                total = app.epistola.suite.generation.collect.domain.Partition.TOTAL_PARTITIONS,
                mine = emptyList(),
                hash = WirePartitionAssignment.Hash.MURMUR3,
            )
        }

        return ResponseEntity.ok(
            PongResponse(
                status = PongResponse.Status.UP,
                timestamp = now,
                details = PongDetailsDto(
                    serverVersion = info.serverVersion,
                    apiVersion = info.apiVersion,
                    nodeId = info.nodeId,
                    partitions = partitions,
                ),
            ),
        )
    }

    private fun currentRequest(): HttpServletRequest? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    private companion object {
        const val HEADER_NODE_ID = "X-EP-Node-Id"
    }
}
