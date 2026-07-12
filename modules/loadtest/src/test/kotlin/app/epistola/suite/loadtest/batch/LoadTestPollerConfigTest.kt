package app.epistola.suite.loadtest.batch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The stale-run recovery timeout must stay above the executor's progress-heartbeat
 * cadence; a timeout of zero (or less) would let recovery fire on a run that is
 * still being driven, re-submitting a whole second batch and corrupting metrics
 * (#725). This guards the configuration at construction.
 */
class LoadTestPollerConfigTest {

    @Test
    fun `rejects a stale timeout below one minute`() {
        assertThatThrownBy { LoadTestPoller.validateStaleTimeoutMinutes(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("stale-timeout-minutes")
    }

    @Test
    fun `accepts a sane stale timeout`() {
        // Does not throw for the default and any reasonable override.
        LoadTestPoller.validateStaleTimeoutMinutes(1)
        LoadTestPoller.validateStaleTimeoutMinutes(10)
        assertThat(LoadTestExecutor.POLL_INTERVAL_MS).isLessThan(60_000L)
    }
}
