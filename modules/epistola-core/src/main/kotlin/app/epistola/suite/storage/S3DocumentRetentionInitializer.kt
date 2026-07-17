package app.epistola.suite.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration
import software.amazon.awssdk.services.s3.model.ExpirationStatus
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.LifecycleExpiration
import software.amazon.awssdk.services.s3.model.LifecycleRule
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * Enforces document-blob retention natively on the S3 backend (issue #738) by
 * reconciling a bucket **lifecycle rule** that expires objects under the `documents/`
 * prefix after the retention window. S3 then deletes aged document blobs itself — no
 * app-driven sweep. Asset blobs live in PostgreSQL, never in the bucket, so this rule
 * can never touch permanent data.
 *
 * **Amend, don't replace.** S3's `PutBucketLifecycleConfiguration` overwrites the whole
 * bucket configuration, so this reads the existing rules first and merges our single
 * rule in **by id** — any other rules the operator has on the bucket are preserved.
 * Idempotent: re-running updates only our rule to the desired state. A missing
 * `s3:GetLifecycleConfiguration` / `s3:PutLifecycleConfiguration` permission is logged
 * and tolerated (document blobs simply won't auto-expire) rather than failing boot.
 * Disable entirely with `epistola.storage.s3.manage-document-lifecycle=false`.
 */
class S3DocumentRetentionInitializer(
    private val s3Client: S3Client,
    private val bucket: String,
    private val documentRetentionDays: Int,
) : SmartInitializingSingleton {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        val ourRule = LifecycleRule.builder()
            .id(RULE_ID)
            .status(ExpirationStatus.ENABLED)
            .filter(LifecycleRuleFilter.builder().prefix(DOCUMENTS_PREFIX).build())
            .expiration(LifecycleExpiration.builder().days(documentRetentionDays).build())
            .build()
        try {
            // Preserve every other rule; replace only ours (by id).
            val others = existingRules().filter { it.id() != RULE_ID }
            s3Client.putBucketLifecycleConfiguration(
                PutBucketLifecycleConfigurationRequest.builder()
                    .bucket(bucket)
                    .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(others + ourRule).build())
                    .build(),
            )
            logger.info(
                "Ensured S3 lifecycle rule '{}' on bucket {} — expire prefix '{}' after {} days (preserved {} other rule(s))",
                RULE_ID,
                bucket,
                DOCUMENTS_PREFIX,
                documentRetentionDays,
                others.size,
            )
        } catch (e: Exception) {
            logger.warn(
                "Could not set S3 lifecycle rule on bucket {} (document blobs will not auto-expire): {}",
                bucket,
                e.message,
            )
        }
    }

    /** Current bucket lifecycle rules, or empty when none is configured yet. */
    private fun existingRules(): List<LifecycleRule> = try {
        s3Client.getBucketLifecycleConfiguration(
            GetBucketLifecycleConfigurationRequest.builder().bucket(bucket).build(),
        ).rules()
    } catch (e: S3Exception) {
        // A bucket with no lifecycle config yet returns NoSuchLifecycleConfiguration.
        if (e.awsErrorDetails()?.errorCode() == "NoSuchLifecycleConfiguration") {
            emptyList()
        } else {
            throw e
        }
    }

    private companion object {
        const val RULE_ID = "epistola-document-content-retention"
        const val DOCUMENTS_PREFIX = "documents/"
    }
}
