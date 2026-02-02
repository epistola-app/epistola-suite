package app.epistola.suite.common.ids

import app.epistola.suite.common.UUIDv7
import java.util.UUID

/**
 * Base interface for all typed entity IDs.
 * Provides compile-time type safety to prevent accidental misuse of IDs
 * (e.g., passing a TemplateId where a TenantId is expected).
 */
sealed interface EntityId<T : EntityId<T>> {
    val value: UUID
}

/**
 * Typed ID for Tenant entities.
 */
@JvmInline
value class TenantId(override val value: UUID) : EntityId<TenantId> {
    companion object {
        fun generate(): TenantId = TenantId(UUIDv7.generate())
        fun of(value: UUID): TenantId = TenantId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for DocumentTemplate entities.
 */
@JvmInline
value class TemplateId(override val value: UUID) : EntityId<TemplateId> {
    companion object {
        fun generate(): TemplateId = TemplateId(UUIDv7.generate())
        fun of(value: UUID): TemplateId = TemplateId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for TemplateVariant entities.
 */
@JvmInline
value class VariantId(override val value: UUID) : EntityId<VariantId> {
    companion object {
        fun generate(): VariantId = VariantId(UUIDv7.generate())
        fun of(value: UUID): VariantId = VariantId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for TemplateVersion entities.
 */
@JvmInline
value class VersionId(override val value: UUID) : EntityId<VersionId> {
    companion object {
        fun generate(): VersionId = VersionId(UUIDv7.generate())
        fun of(value: UUID): VersionId = VersionId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for Environment entities.
 */
@JvmInline
value class EnvironmentId(override val value: UUID) : EntityId<EnvironmentId> {
    companion object {
        fun generate(): EnvironmentId = EnvironmentId(UUIDv7.generate())
        fun of(value: UUID): EnvironmentId = EnvironmentId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for Document entities.
 */
@JvmInline
value class DocumentId(override val value: UUID) : EntityId<DocumentId> {
    companion object {
        fun generate(): DocumentId = DocumentId(UUIDv7.generate())
        fun of(value: UUID): DocumentId = DocumentId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for DocumentGenerationRequest entities.
 */
@JvmInline
value class GenerationRequestId(override val value: UUID) : EntityId<GenerationRequestId> {
    companion object {
        fun generate(): GenerationRequestId = GenerationRequestId(UUIDv7.generate())
        fun of(value: UUID): GenerationRequestId = GenerationRequestId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for DocumentGenerationItem entities.
 */
@JvmInline
value class GenerationItemId(override val value: UUID) : EntityId<GenerationItemId> {
    companion object {
        fun generate(): GenerationItemId = GenerationItemId(UUIDv7.generate())
        fun of(value: UUID): GenerationItemId = GenerationItemId(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for Theme entities.
 */
@JvmInline
value class ThemeId(override val value: UUID) : EntityId<ThemeId> {
    companion object {
        fun generate(): ThemeId = ThemeId(UUIDv7.generate())
        fun of(value: UUID): ThemeId = ThemeId(value)
    }

    override fun toString(): String = value.toString()
}
