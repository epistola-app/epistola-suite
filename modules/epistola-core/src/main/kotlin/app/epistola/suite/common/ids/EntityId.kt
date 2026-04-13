package app.epistola.suite.common.ids

import com.fasterxml.jackson.annotation.JsonValue
import java.util.UUID

abstract class EntityIdBase(private val parent: EntityIdBase?) {

    /** Most-specific id type name, used in the URN (e.g. "tenant", "user", "variant") */
    protected abstract val type: String

    /** One segment for this id in the hierarchical path */
    protected abstract fun segment(): String

    /** Path without type prefix, e.g. tenantA/email/v1 */
    fun path(): String = "$type:" + generateSequence(this) { it.parent }
        .toList()
        .asReversed()
        .joinToString("/") { it.segment() }

    /** Full URN, e.g. urn:epistola:variant:tenantA/email/v1 */
    fun toUrn(nid: String = "epistola"): String = "urn:$nid:${path()}"

    @JsonValue
    override fun toString() = path()
}

abstract class EntityId<T : EntityKey<T, V>, V : Any, P : EntityIdBase?>(
    open val key: T,
    parent: P,
) : EntityIdBase(parent) {
    override fun segment(): String = key.value.toString()
}

class TenantId(key: TenantKey) : EntityId<TenantKey, String, Nothing?>(key, null) {
    override val type = "tenant"
}

/**
 * Identifies a catalog within a tenant. Part of the resource identity chain:
 * TenantId → CatalogId → ResourceId (Template, Theme, Stencil, etc.)
 */
data class CatalogId(
    override val key: CatalogKey,
    val tenantId: TenantId,
) : EntityId<CatalogKey, String, TenantId>(key, tenantId) {
    override val type = "catalog"
    val tenantKey get() = tenantId.key

    companion object {
        fun default(tenantId: TenantId) = CatalogId(CatalogKey.DEFAULT, tenantId)
    }
}

class UserId(key: UserKey, tenantId: TenantId) : EntityId<UserKey, UUID, TenantId>(key, tenantId) {
    override val type = "user"
    val tenantKey = tenantId.key
}

data class TemplateId(
    override val key: TemplateKey,
    val catalogId: CatalogId,
) : EntityId<TemplateKey, String, CatalogId>(key, catalogId) {
    override val type = "template"
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
}

class VariantId(key: VariantKey, val templateId: TemplateId) : EntityId<VariantKey, String, TemplateId>(key, templateId) {
    override val type = "variant"
    val catalogId get() = templateId.catalogId
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
    val templateKey = templateId.key
}

class VersionId(key: VersionKey, val variantId: VariantId) : EntityId<VersionKey, Int, VariantId>(key, variantId) {
    override val type = "version"

    val catalogId get() = variantId.catalogId
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
    val templateKey = variantId.templateKey
    val variantKey = variantId.key
}

data class StencilId(
    override val key: StencilKey,
    val catalogId: CatalogId,
) : EntityId<StencilKey, String, CatalogId>(key, catalogId) {
    override val type = "stencil"
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
}

class StencilVersionId(key: VersionKey, val stencilId: StencilId) : EntityId<VersionKey, Int, StencilId>(key, stencilId) {
    override val type = "stencil-version"
    val catalogId get() = stencilId.catalogId
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
    val stencilKey = stencilId.key
}

class ThemeId(
    key: ThemeKey,
    val catalogId: CatalogId,
) : EntityId<ThemeKey, String, CatalogId>(key, catalogId) {
    override val type = "theme"
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
}

class EnvironmentId(key: EnvironmentKey, tenantId: TenantId) : EntityId<EnvironmentKey, String, TenantId>(key, tenantId) {
    override val type = "environment"
    val tenantKey = tenantId.key
}

class AttributeId(
    key: AttributeKey,
    val catalogId: CatalogId,
) : EntityId<AttributeKey, String, CatalogId>(key, catalogId) {
    override val type = "attribute"
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
}

class AssetId(
    key: AssetKey,
    val catalogId: CatalogId,
) : EntityId<AssetKey, UUID, CatalogId>(key, catalogId) {
    override val type = "asset"
    val tenantId get() = catalogId.tenantId
    val tenantKey get() = catalogId.tenantKey
    val catalogKey get() = catalogId.key
}

class DocumentId(key: DocumentKey, tenantId: TenantId) : EntityId<DocumentKey, UUID, TenantId>(key, tenantId) {
    override val type = "document"
}

class GenerationRequestId(key: GenerationRequestKey, tenantId: TenantId) : EntityId<GenerationRequestKey, UUID, TenantId>(key, tenantId) {
    override val type = "generation-request"
    val tenantKey = tenantId.key
}

class BatchId(key: BatchKey, tenantId: TenantId) : EntityId<BatchKey, UUID, TenantId>(key, tenantId) {
    override val type = "batch"
    val tenantKey = tenantId.key
}

class ApiKeyId(key: ApiKeyKey, tenantId: TenantId) : EntityId<ApiKeyKey, UUID, TenantId>(key, tenantId) {
    override val type = "api-key"
    val tenantKey = tenantId.key
}

class FeedbackId(key: FeedbackKey, tenantId: TenantId) : EntityId<FeedbackKey, UUID, TenantId>(key, tenantId) {
    override val type = "feedback"
    val tenantKey = tenantId.key
}

class FeedbackCommentId(key: FeedbackCommentKey, feedbackId: FeedbackId) : EntityId<FeedbackCommentKey, UUID, FeedbackId>(key, feedbackId) {
    override val type = "feedback-comment"
    val feedbackKey = feedbackId.key
    val tenantKey = feedbackId.tenantKey
}

class FeedbackAssetId(key: FeedbackAssetKey, feedbackId: FeedbackId) : EntityId<FeedbackAssetKey, UUID, FeedbackId>(key, feedbackId) {
    override val type = "feedback-asset"
    val feedbackKey = feedbackId.key
    val tenantKey = feedbackId.tenantKey
}
