package app.epistola.suite.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ExpirationStatus
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.GetBucketLifecycleConfigurationResponse
import software.amazon.awssdk.services.s3.model.LifecycleExpiration
import software.amazon.awssdk.services.s3.model.LifecycleRule
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * The S3 document-retention initializer must **amend** the bucket lifecycle config, not
 * replace it — preserving any other rules the operator has (#738).
 */
class S3DocumentRetentionInitializerTest {

    private val s3 = mock(S3Client::class.java)

    @Test
    fun `merges our rule while preserving other bucket rules`() {
        val other = rule("keep-me", "archive/", days = 9000)
        val staleOurs = rule(RULE_ID, "documents/", days = 1) // an outdated previous version of ours
        given(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest::class.java)))
            .willReturn(GetBucketLifecycleConfigurationResponse.builder().rules(other, staleOurs).build())

        S3DocumentRetentionInitializer(s3, "bucket", documentRetentionDays = 93).afterSingletonsInstantiated()

        val rules = capturePut().lifecycleConfiguration().rules()
        // The operator's rule survives; ours appears exactly once, updated to 93 days.
        assertThat(rules.map { it.id() }).containsExactly("keep-me", RULE_ID)
        val ours = rules.first { it.id() == RULE_ID }
        assertThat(ours.expiration().days()).isEqualTo(93)
        assertThat(ours.filter().prefix()).isEqualTo("documents/")
    }

    @Test
    fun `treats a bucket with no lifecycle config as empty`() {
        val notConfigured = S3Exception.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchLifecycleConfiguration").build())
            .build() as S3Exception
        given(s3.getBucketLifecycleConfiguration(any(GetBucketLifecycleConfigurationRequest::class.java)))
            .willThrow(notConfigured)

        S3DocumentRetentionInitializer(s3, "bucket", documentRetentionDays = 93).afterSingletonsInstantiated()

        assertThat(capturePut().lifecycleConfiguration().rules().map { it.id() }).containsExactly(RULE_ID)
    }

    private fun capturePut(): PutBucketLifecycleConfigurationRequest {
        val captor = ArgumentCaptor.forClass(PutBucketLifecycleConfigurationRequest::class.java)
        verify(s3).putBucketLifecycleConfiguration(captor.capture())
        return captor.value
    }

    private fun rule(id: String, prefix: String, days: Int): LifecycleRule = LifecycleRule.builder()
        .id(id)
        .status(ExpirationStatus.ENABLED)
        .filter(LifecycleRuleFilter.builder().prefix(prefix).build())
        .expiration(LifecycleExpiration.builder().days(days).build())
        .build()

    private companion object {
        const val RULE_ID = "epistola-document-content-retention"
    }
}
