package app.epistola.suite.common.ids

import java.util.UUID

abstract class EntityIdBase(open val parent: EntityIdBase?) {

    /** Most-specific id type name, used in the URN (e.g. "tenant", "user", "variant") */
    protected abstract val type: String

    /** One segment for this id in the hierarchical path */
    protected abstract fun segment(): String

    /** Path without type prefix, e.g. tenantA/email/v1 */
    fun path(): String = generateSequence(this) { it.parent }
        .toList()
        .asReversed()
        .joinToString("/") { it.segment() }

    /** Full URN, e.g. urn:epistola:variant:tenantA/email/v1 */
    fun toUrn(nid: String = "epistola"): String = "urn:$nid:$type:${path()}"
}

abstract class EntityId<T : EntityKey<T, V>, V : Any, P : EntityIdBase?>(
    val key: T,
    override val parent: P,
) : EntityIdBase(parent) {
    override fun segment(): String = key.value.toString()
}

class TenantId(key: TenantKey) : EntityId<TenantKey, String, Nothing?>(key, null) {
    override val type = "tenant"
}

class UserId(key: UserKey, parent: TenantId) : EntityId<UserKey, UUID, TenantId>(key, parent) {
    override val type = "user"
}

class TemplateId(key: TemplateKey, parent: TenantId) : EntityId<TemplateKey, String, TenantId>(key, parent) {
    override val type = "template"
}

class VariantId(key: VariantKey, parent: TemplateId) : EntityId<VariantKey, String, TemplateId>(key, parent) {
    override val type = "variant"
}

class VersionId(key: VersionKey, parent: VariantId) : EntityId<VersionKey, Int, VariantId>(key, parent) {
    override val type = "version"
}

class ThemeId(key: ThemeKey, parent: TenantId) : EntityId<ThemeKey, String, TenantId>(key, parent) {
    override val type = "theme"
}

class EnvironmentId(key: EnvironmentKey, parent: TenantId) : EntityId<EnvironmentKey, String, TenantId>(key, parent) {
    override val type = "environment"
}

class AttributeId(key: AttributeKey, parent: TenantId) : EntityId<AttributeKey, String, TenantId>(key, parent) {
    override val type = "attribute"
}

class AssetId(key: AssetKey) : EntityId<AssetKey, UUID, Nothing?>(key, null) {
    override val type = "asset"
}

class DocumentId(key: DocumentKey) : EntityId<DocumentKey, UUID, Nothing?>(key, null) {
    override val type = "document"
}

class GenerationRequestId(key: GenerationRequestKey) : EntityId<GenerationRequestKey, UUID, Nothing?>(key, null) {
    override val type = "generation-request"
}

class BatchId(key: BatchKey) : EntityId<BatchKey, UUID, Nothing?>(key, null) {
    override val type = "batch"
}

class ApiKeyId(key: ApiKeyKey, parent: TenantId) : EntityId<ApiKeyKey, UUID, TenantId>(key, parent) {
    override val type = "api-key"
}
