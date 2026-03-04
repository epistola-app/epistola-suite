package app.epistola.suite.common.ids

import app.epistola.suite.common.UUIDv7
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.io.Serializable
import java.util.UUID

/**
 * Base interface for all typed entity IDs.
 * Provides compile-time type safety to prevent accidental misuse of IDs
 * (e.g., passing a TemplateKey where a TenantKey is expected).
 *
 * Generic over both the concrete key type (T) and the value type (V).
 * Extends [Serializable] so keys can be stored in JDBC-backed HTTP sessions
 * (e.g., as part of [EpistolaPrincipal] in the Spring Security context).
 */
interface EntityKey<T : EntityKey<T, V>, V : Any> : Serializable {
    val value: V
}

/** Marker interface for slug-based keys (String value). */
interface SlugKey<T : SlugKey<T>> : EntityKey<T, String>

/** Marker interface for numeric keys like incrementing numbers. */
interface NumericKey<T : NumericKey<T>> : EntityKey<T, Int>

/** Marker interface for UUID-based keys. */
interface UuidKey<T : UuidKey<T>> : EntityKey<T, UUID>

/**
 * Typed key for Tenant entities.
 */
@JvmInline
value class TenantKey(@JsonValue override val value: String) : SlugKey<TenantKey> {
    init {
        require(value.length in 3..63) {
            "Tenant ID must be 3-63 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Tenant ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
        require(value !in RESERVED_WORDS) {
            "Tenant ID '$value' is reserved and cannot be used"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val RESERVED_WORDS = setOf(
            "admin",
            "api",
            "www",
            "system",
            "internal",
            "null",
            "undefined",
        )

        fun of(value: String): TenantKey = TenantKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): TenantKey = TenantKey(value)

        fun validateOrNull(value: String): TenantKey? = runCatching { TenantKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed key for VariantAttributeDefinition entities.
 */
@JvmInline
value class AttributeKey(@JsonValue override val value: String) : SlugKey<AttributeKey> {
    init {
        require(value.length in 3..50) {
            "Attribute ID must be 3-50 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Attribute ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

        fun of(value: String): AttributeKey = AttributeKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): AttributeKey = AttributeKey(value)

        fun validateOrNull(value: String): AttributeKey? = runCatching { AttributeKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed key for DocumentTemplate entities.
 */
@JvmInline
value class TemplateKey(@JsonValue override val value: String) : SlugKey<TemplateKey> {
    init {
        require(value.length in 3..50) {
            "Template ID must be 3-50 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Template ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
        require(value !in RESERVED_WORDS) {
            "Template ID '$value' is reserved and cannot be used"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val RESERVED_WORDS = setOf(
            "admin", "api", "www", "system", "internal", "null", "undefined",
            "new", "create", "edit", "delete",
        )

        fun of(value: String): TemplateKey = TemplateKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): TemplateKey = TemplateKey(value)

        fun validateOrNull(value: String): TemplateKey? = runCatching { TemplateKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed key for TemplateVariant entities.
 */
@JvmInline
value class VariantKey(@JsonValue override val value: String) : SlugKey<VariantKey> {
    init {
        require(value.length in 3..50) {
            "Variant ID must be 3-50 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Variant ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
        require(value !in RESERVED_WORDS) {
            "Variant ID '$value' is reserved and cannot be used"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val RESERVED_WORDS = setOf(
            "admin", "api", "www", "system", "internal", "null", "undefined",
            "new", "create", "edit", "delete",
        )

        fun of(value: String): VariantKey = VariantKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): VariantKey = VariantKey(value)

        fun validateOrNull(value: String): VariantKey? = runCatching { VariantKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed key for TemplateVersion entities (auto-incrementing ints per variant).
 */
@JvmInline
value class VersionKey(@JsonValue override val value: Int) : NumericKey<VersionKey> {
    init {
        require(value in MIN_VERSION..MAX_VERSION) {
            "Version ID must be between $MIN_VERSION and $MAX_VERSION, got $value"
        }
    }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 200

        fun of(value: Int): VersionKey = VersionKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: Int): VersionKey = VersionKey(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for Environment entities.
 */
@JvmInline
value class EnvironmentKey(@JsonValue override val value: String) : SlugKey<EnvironmentKey> {
    init {
        require(value.length in 3..30) {
            "Environment ID must be 3-30 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Environment ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
        require(value !in RESERVED_WORDS) {
            "Environment ID '$value' is reserved and cannot be used"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        private val RESERVED_WORDS = setOf(
            "admin",
            "api",
            "www",
            "system",
            "internal",
            "null",
            "undefined",
        )

        fun of(value: String): EnvironmentKey = EnvironmentKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): EnvironmentKey = EnvironmentKey(value)

        fun validateOrNull(value: String): EnvironmentKey? = runCatching { EnvironmentKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed key for Theme entities.
 */
@JvmInline
value class ThemeKey(@JsonValue override val value: String) : SlugKey<ThemeKey> {
    init {
        require(value.length in 3..20) {
            "Theme ID must be 3-20 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Theme ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

        fun of(value: String): ThemeKey = ThemeKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): ThemeKey = ThemeKey(value)

        fun validateOrNull(value: String): ThemeKey? = runCatching { ThemeKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed key for Asset entities.
 */
@JvmInline
value class AssetKey(@JsonValue override val value: UUID) : UuidKey<AssetKey> {
    companion object {
        fun generate(): AssetKey = AssetKey(UUIDv7.generate())
        fun of(value: UUID): AssetKey = AssetKey(value)
        fun of(value: String): AssetKey = AssetKey(UUID.fromString(value))

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): AssetKey = AssetKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for Document entities.
 */
@JvmInline
value class DocumentKey(@JsonValue override val value: UUID) : UuidKey<DocumentKey> {
    companion object {
        fun generate(): DocumentKey = DocumentKey(UUIDv7.generate())
        fun of(value: UUID): DocumentKey = DocumentKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): DocumentKey = DocumentKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for DocumentGenerationRequest entities.
 */
@JvmInline
value class GenerationRequestKey(@JsonValue override val value: UUID) : UuidKey<GenerationRequestKey> {
    companion object {
        fun generate(): GenerationRequestKey = GenerationRequestKey(UUIDv7.generate())
        fun of(value: UUID): GenerationRequestKey = GenerationRequestKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): GenerationRequestKey = GenerationRequestKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for DocumentGenerationBatch entities.
 */
@JvmInline
value class BatchKey(@JsonValue override val value: UUID) : UuidKey<BatchKey> {
    companion object {
        fun generate(): BatchKey = BatchKey(UUIDv7.generate())
        fun of(value: UUID): BatchKey = BatchKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): BatchKey = BatchKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for API key entities.
 */
@JvmInline
value class ApiKeyKey(@JsonValue override val value: UUID) : UuidKey<ApiKeyKey> {
    companion object {
        fun generate(): ApiKeyKey = ApiKeyKey(UUIDv7.generate())
        fun of(value: UUID): ApiKeyKey = ApiKeyKey(value)
        fun of(value: String): ApiKeyKey = ApiKeyKey(UUID.fromString(value))

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): ApiKeyKey = ApiKeyKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for User entities.
 */
@JvmInline
value class UserKey(@JsonValue override val value: UUID) : UuidKey<UserKey> {
    companion object {
        fun generate(): UserKey = UserKey(UUIDv7.generate())
        fun of(value: UUID): UserKey = UserKey(value)
        fun of(value: String): UserKey = UserKey(UUID.fromString(value))

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): UserKey = UserKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for Feedback entities.
 */
@JvmInline
value class FeedbackKey(@JsonValue override val value: UUID) : UuidKey<FeedbackKey> {
    companion object {
        fun generate(): FeedbackKey = FeedbackKey(UUIDv7.generate())
        fun of(value: UUID): FeedbackKey = FeedbackKey(value)
        fun of(value: String): FeedbackKey = FeedbackKey(UUID.fromString(value))

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): FeedbackKey = FeedbackKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed key for FeedbackComment entities.
 */
@JvmInline
value class FeedbackCommentKey(@JsonValue override val value: UUID) : UuidKey<FeedbackCommentKey> {
    companion object {
        fun generate(): FeedbackCommentKey = FeedbackCommentKey(UUIDv7.generate())
        fun of(value: UUID): FeedbackCommentKey = FeedbackCommentKey(value)
        fun of(value: String): FeedbackCommentKey = FeedbackCommentKey(UUID.fromString(value))

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): FeedbackCommentKey = FeedbackCommentKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
