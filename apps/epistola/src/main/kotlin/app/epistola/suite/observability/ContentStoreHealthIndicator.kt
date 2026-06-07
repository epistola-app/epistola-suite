package app.epistola.suite.observability

import app.epistola.suite.storage.ContentStore
import app.epistola.suite.storage.StorageProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Reports whether the configured content-storage backend is reachable, by
 * issuing a cheap, non-mutating existence probe. Surfaces an S3 / filesystem /
 * database storage outage in `/actuator/health` so it can be alerted on across
 * a remote fleet.
 *
 * Deliberately contributes to the **overall** health endpoint only, NOT the
 * Kubernetes readiness group: a transient storage blip should not pull an
 * otherwise-serving pod (its UI and API still work) out of rotation, and the
 * condition is observable/alertable either way. The probe goes through the
 * instrumented store, so it also shows up as an `exists` storage metric — kept
 * cheap and out of the 10s readiness loop for exactly that reason.
 */
@Component
class ContentStoreHealthIndicator(
    private val contentStore: ContentStore,
    private val storageProperties: StorageProperties,
) : HealthIndicator {

    override fun health(): Health {
        val backend = storageProperties.backend.name
        return try {
            contentStore.exists(PROBE_KEY)
            Health.up().withDetail("backend", backend).build()
        } catch (e: Exception) {
            Health.down().withException(e).withDetail("backend", backend).build()
        }
    }

    private companion object {
        /** Fixed, never-written key — exercises the backend round-trip without mutating it. */
        const val PROBE_KEY = "__health__/reachability-probe"
    }
}
