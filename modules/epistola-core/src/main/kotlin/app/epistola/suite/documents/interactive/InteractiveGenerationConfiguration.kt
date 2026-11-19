// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.documents.cleanup.PartitionProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Interactive generation's settings, checked at boot against partition retention: a waiting request
 * lives in its creation month's partition, so retention must keep that partition until the longest
 * allowed wait has passed, or a request could be dropped while its form is still open.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InteractiveGenerationProperties::class)
class InteractiveGenerationConfiguration(properties: InteractiveGenerationProperties, partitions: PartitionProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (!properties.configured) {
            log.info("Interactive generation is off: epistola.generation.interactive.public-base-url is not set")
        } else {
            // A partition is dropped once it is older than the retention; its last request may have been
            // created on its last day, so the shortest life of a request is (retention - 1) whole months.
            val shortestLife = Duration.ofDays(SHORTEST_MONTH_DAYS * (partitions.retentionMonths - 1).toLong())
            require(shortestLife >= properties.maxExpiry) {
                "epistola.partitions.retention-months (${partitions.retentionMonths}) is too short for " +
                    "epistola.generation.interactive.max-expiry (${properties.maxExpiry}): a waiting request could be " +
                    "dropped before its form expires. Raise the retention or lower the maximum expiry."
            }
        }
    }

    private companion object {
        const val SHORTEST_MONTH_DAYS = 28
    }
}
