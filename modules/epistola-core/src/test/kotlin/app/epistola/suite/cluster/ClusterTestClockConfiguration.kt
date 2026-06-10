package app.epistola.suite.cluster

import app.epistola.suite.testing.MutableClock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant

@TestConfiguration
class ClusterTestClockConfiguration {
    @Bean
    @Primary
    fun clusterTestClock(): MutableClock = MutableClock(Instant.parse("2026-06-10T00:00:00Z"))
}
