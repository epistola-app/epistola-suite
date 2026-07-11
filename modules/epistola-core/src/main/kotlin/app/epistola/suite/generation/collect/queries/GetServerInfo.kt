package app.epistola.suite.generation.collect.queries

import app.epistola.api.identity.ServerContractInfo
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.security.SystemInternal
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component

/**
 * Server identity for the v0.3 `/ping` endpoint:
 *
 *   - serverVersion — the running suite's build version (Gradle build-info).
 *   - apiVersion    — the contract spec version this build implements, reported
 *                     by the contract library itself via
 *                     [ServerContractInfo.contractVersion] (written into the
 *                     contract JAR at build time from the OpenAPI spec's
 *                     `info.version`). The contract is the source of truth;
 *                     hardcoding it in the suite would be one place to forget
 *                     to bump on every contract upgrade.
 *   - nodeId        — opaque per-process identifier (see [NodeIdentity]).
 *
 * SystemInternal — `/ping` may be called unauthenticated, and even when
 * authenticated the response is the same. There's no tenant or user context.
 */
class GetServerInfo :
    Query<ServerInfo>,
    SystemInternal

data class ServerInfo(
    val serverVersion: String,
    val apiVersion: String,
    val nodeId: String,
)

@Component
class GetServerInfoHandler(
    // BuildProperties is bean-conditional on `build-info.properties` existing
    // (it does in the production build but may be absent in unit-test contexts).
    // Optional injection keeps unit tests from needing the full build.
    private val buildProperties: BuildProperties? = null,
    private val nodeIdentity: NodeIdentity,
) : QueryHandler<GetServerInfo, ServerInfo> {

    override fun handle(query: GetServerInfo): ServerInfo {
        val serverVersion = buildProperties?.version ?: "dev"
        return ServerInfo(
            serverVersion = serverVersion,
            apiVersion = ServerContractInfo.contractVersion,
            nodeId = nodeIdentity.nodeId,
        )
    }
}
