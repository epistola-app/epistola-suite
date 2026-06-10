package app.epistola.suite.time

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@Configuration
class EpistolaClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun epistolaClock(): Clock = DelegatingEpistolaClock
}

private object DelegatingEpistolaClock : Clock() {
    override fun getZone(): ZoneId = EpistolaClock.current().zone

    override fun withZone(zone: ZoneId): Clock = EpistolaClock.current().withZone(zone)

    override fun instant(): Instant = EpistolaClock.instant()
}
