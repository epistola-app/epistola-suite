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

class UserId(key: UserKey, tenantId: TenantId) : EntityId<UserKey, UUID, TenantId>(key, tenantId) {
    override val type = "user"
    val tenantKey = tenantId.key
}

data class TemplateId(override val key: TemplateKey, val tenantId: TenantId) : EntityId<TemplateKey, String, TenantId>(key, tenantId) {
    override val type = "template"
    val tenantKey = tenantId.key
}

class VariantId(key: VariantKey, templateId: TemplateId) : EntityId<VariantKey, String, TemplateId>(key, templateId) {
    override val type = "variant"
    val tenantKey = templateId.tenantKey
    val templateKey = templateId.key
}

class VersionId(key: VersionKey, variantId: VariantId) : EntityId<VersionKey, Int, VariantId>(key, variantId) {
    override val type = "version"

    val tenantKey = variantId.tenantKey
    val templateKey = variantId.templateKey
    val variantKey = variantId.key
}

class ThemeId(key: ThemeKey, tenantId: TenantId) : EntityId<ThemeKey, String, TenantId>(key, tenantId) {
    override val type = "theme"
    val tenantKey = tenantId.key
}

class EnvironmentId(key: EnvironmentKey, tenantId: TenantId) : EntityId<EnvironmentKey, String, TenantId>(key, tenantId) {
    override val type = "environment"
    val tenantKey = tenantId.key
}

class AttributeId(key: AttributeKey, tenantId: TenantId) : EntityId<AttributeKey, String, TenantId>(key, tenantId) {
    override val type = "attribute"
    val tenantKey = tenantId.key
}

class AssetId(key: AssetKey, tenantId: TenantId) : EntityId<AssetKey, UUID, TenantId>(key, tenantId) {
    override val type = "asset"
    val tenantKey = tenantId.key
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
