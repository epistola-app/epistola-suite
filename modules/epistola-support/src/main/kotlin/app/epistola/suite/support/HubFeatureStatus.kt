package app.epistola.suite.support

import app.epistola.hub.client.error.HubEntitlementDeniedException
import app.epistola.hub.client.error.HubException
import app.epistola.hub.client.error.HubUnavailableException
import org.slf4j.Logger

/**
 * Outcome of calling a hub-backed support feature (Backups, Upgrading), as a single status the UI
 * switches on instead of several booleans.
 *
 * The distinction that matters: [UNAVAILABLE] means the hub couldn't be reached at all, whereas
 * [ERROR] means it *answered* but couldn't serve the request (e.g. an older hub without catalog
 * sync returns gRPC `UNIMPLEMENTED`, or a server error) — so the connection status and the feature
 * status don't contradict each other.
 */
enum class HubFeatureStatus {
    /** The hub served the request. */
    OK,

    /** The hub couldn't be reached (gRPC `UNAVAILABLE` / connect failure). */
    UNAVAILABLE,

    /** The hub answered, but the installation isn't entitled to the feature (`PERMISSION_DENIED`). */
    NOT_ENTITLED,

    /** The hub answered but couldn't serve the request (`UNIMPLEMENTED`, server error, …). */
    ERROR,
}

/** A hub feature call's [status] plus its [value] on success (null otherwise) and the [error] when not OK. */
data class HubFeatureResult<T>(
    val status: HubFeatureStatus,
    val value: T?,
    val error: HubException? = null,
)

/**
 * Runs [block] and classifies the outcome into a [HubFeatureStatus], translating the typed hub
 * exceptions so callers (UI handlers) get one status to switch on rather than catching each type.
 */
fun <T> hubFeatureCall(block: () -> T): HubFeatureResult<T> = try {
    HubFeatureResult(HubFeatureStatus.OK, block())
} catch (e: HubEntitlementDeniedException) {
    HubFeatureResult(HubFeatureStatus.NOT_ENTITLED, null, e)
} catch (e: HubUnavailableException) {
    HubFeatureResult(HubFeatureStatus.UNAVAILABLE, null, e)
} catch (e: HubException) {
    HubFeatureResult(HubFeatureStatus.ERROR, null, e)
}

/** Logs a non-OK hub feature outcome at the appropriate level (unreachable/error = warn, not-entitled = debug). */
fun HubFeatureResult<*>.logTo(
    logger: Logger,
    tenant: String,
    feature: String,
) {
    when (status) {
        HubFeatureStatus.OK -> {}
        HubFeatureStatus.NOT_ENTITLED -> logger.debug("Tenant {} is not entitled to {}: {}", tenant, feature, error?.message)
        HubFeatureStatus.UNAVAILABLE -> logger.warn("Could not reach the Epistola hub for tenant {} {}: {}", tenant, feature, error?.message)
        HubFeatureStatus.ERROR -> logger.warn("Epistola hub could not serve {} for tenant {}: {}", feature, tenant, error?.message)
    }
}
