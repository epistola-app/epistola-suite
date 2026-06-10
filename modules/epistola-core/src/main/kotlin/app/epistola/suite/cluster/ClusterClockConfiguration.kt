package app.epistola.suite.cluster

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClusterClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clusterClock(): Clock = Clock.systemUTC()
}
