// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.cleanup

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Monthly partitions and how long they are kept (`epistola.partitions`). One definition for the
 * partition maintenance, the document retention reaper and the S3 lifecycle rule, which all read
 * [retentionMonths].
 *
 * @property enabled whether the partition maintenance runs at all.
 * @property retentionMonths how long `documents`, `document_content` and `document_generation_requests` are kept.
 * @property generationResultsRetentionMonths how long the collect feed's `generation_results` are kept.
 * @property eventLogRetentionMonths how long the `event_log` command record is kept.
 * @property maintenanceCron when the maintenance creates and drops partitions.
 */
@ConfigurationProperties(prefix = "epistola.partitions")
data class PartitionProperties(
    val enabled: Boolean = true,
    val retentionMonths: Int = 3,
    val generationResultsRetentionMonths: Int = 1,
    val eventLogRetentionMonths: Int = 6,
    val maintenanceCron: String = "0 0 2 * * ?",
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PartitionProperties::class)
class PartitionConfiguration
