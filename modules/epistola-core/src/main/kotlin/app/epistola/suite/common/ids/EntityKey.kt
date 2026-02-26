package app.epistola.suite.common.ids

import app.epistola.suite.common.UUIDv7
import java.util.UUID

/**
 * Base interface for all typed entity IDs.
 * Provides compile-time type safety to prevent accidental misuse of IDs
 * (e.g., passing a TemplateId where a TenantId is expected).
 *
 * Generic over both the concrete ID type (T) and the value type (V).
 */
interface EntityKey<T : EntityKey<T, V>, V : Any> {
    val value: V
}

/**
 * Marker interface for slug-based IDs (String value).
 * Used for human-readable, URL-safe identifiers.
 */
interface SlugKey<T : SlugKey<T>> : EntityKey<T, String>

/**
 * Marker interface for Numeric Keys like incrementing numbers.
 */
interface NumericKey<T : NumericKey<T>> : EntityKey<T, Int>

/**
 * Marker interface for UUID-based IDs.
 * Used for machine-generated unique identifiers.
 */
interface UuidKey<T : UuidKey<T>> : EntityKey<T, UUID>

/**
 * Typed ID for Tenant entities.
 * Uses a human-readable slug format (e.g., "acme-corp") instead of UUID.
 *
 * Validation rules:
 * - Length: 3-63 characters
 * - Characters: lowercase letters (a-z), numbers (0-9), hyphens (-)
 * - Must start with a letter
 * - Cannot end with a hyphen
 * - No consecutive hyphens
 */
@JvmInline
value class TenantKey(override val value: String) : SlugKey<TenantKey> {
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

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): TenantKey? = runCatching { TenantKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for VariantAttributeDefinition entities.
 * Uses a human-readable slug format (e.g., "language", "brand") instead of UUID.
 *
 * Validation rules:
 * - Length: 3-50 characters
 * - Characters: lowercase letters (a-z), numbers (0-9), hyphens (-)
 * - Must start with a letter
 * - Cannot end with a hyphen
 * - No consecutive hyphens
 */
@JvmInline
value class AttributeKey(override val value: String) : SlugKey<AttributeKey> {
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

        fun validateOrNull(value: String): AttributeKey? = runCatching { AttributeKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for DocumentTemplate entities.
 * Uses a human-readable slug format (e.g., "monthly-invoice") instead of UUID.
 *
 * Validation rules:
 * - Length: 3-50 characters
 * - Characters: lowercase letters (a-z), numbers (0-9), hyphens (-)
 * - Must start with a letter
 * - Cannot end with a hyphen
 * - No consecutive hyphens
 */
@JvmInline
value class TemplateKey(override val value: String) : SlugKey<TemplateKey> {
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
            "admin",
            "api",
            "www",
            "system",
            "internal",
            "null",
            "undefined",
            "new",
            "create",
            "edit",
            "delete",
        )

        fun of(value: String): TemplateKey = TemplateKey(value)

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): TemplateKey? = runCatching { TemplateKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for TemplateVariant entities.
 */
@JvmInline
value class VariantKey(override val value: String) : SlugKey<VariantKey> {
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

        fun validateOrNull(value: String): VariantKey? = runCatching { VariantKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for TemplateVersion entities.
 * Uses auto-incrementing integers (1-200) per variant instead of UUIDs.
 * The version ID IS the version number - no separate version_number field.
 */
@JvmInline
value class VersionKey(override val value: Int) : NumericKey<VersionKey> {
    init {
        require(value in MIN_VERSION..MAX_VERSION) {
            "Version ID must be between $MIN_VERSION and $MAX_VERSION, got $value"
        }
    }

    override fun toString(): String = value.toString()

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 200

        fun of(value: Int): VersionKey = VersionKey(value)

        // No generate() method - version IDs are calculated per variant based on existing versions
    }
}

/**
 * Typed ID for Environment entities.
 * Uses a human-readable slug format (e.g., "production", "staging") instead of UUID.
 *
 * Validation rules:
 * - Length: 3-30 characters
 * - Characters: lowercase letters (a-z), numbers (0-9), hyphens (-)
 * - Must start with a letter
 * - Cannot end with a hyphen
 * - No consecutive hyphens
 */
@JvmInline
value class EnvironmentKey(override val value: String) : SlugKey<EnvironmentKey> {
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

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): EnvironmentKey? = runCatching { EnvironmentKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for Asset entities.
 */
@JvmInline
value class AssetKey(override val value: UUID) : UuidKey<AssetKey> {
    companion object {
        fun generate(): AssetKey = AssetKey(UUIDv7.generate())
        fun of(value: UUID): AssetKey = AssetKey(value)
        fun of(value: String): AssetKey = AssetKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for Document entities.
 */
@JvmInline
value class DocumentKey(override val value: UUID) : UuidKey<DocumentKey> {
    companion object {
        fun generate(): DocumentKey = DocumentKey(UUIDv7.generate())
        fun of(value: UUID): DocumentKey = DocumentKey(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for DocumentGenerationRequest entities.
 */
@JvmInline
value class GenerationRequestKey(override val value: UUID) : UuidKey<GenerationRequestKey> {
    companion object {
        fun generate(): GenerationRequestKey = GenerationRequestKey(UUIDv7.generate())
        fun of(value: UUID): GenerationRequestKey = GenerationRequestKey(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for DocumentGenerationBatch entities.
 * Groups related generation requests together for batch tracking.
 */
@JvmInline
value class BatchKey(override val value: UUID) : UuidKey<BatchKey> {
    companion object {
        fun generate(): BatchKey = BatchKey(UUIDv7.generate())
        fun of(value: UUID): BatchKey = BatchKey(value)
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for Theme entities.
 * Uses a human-readable slug format (e.g., "corporate") instead of UUID.
 *
 * Validation rules:
 * - Length: 3-20 characters
 * - Characters: lowercase letters (a-z), numbers (0-9), hyphens (-)
 * - Must start with a letter
 * - Cannot end with a hyphen
 * - No consecutive hyphens
 */
@JvmInline
value class ThemeKey(override val value: String) : SlugKey<ThemeKey> {
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

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): ThemeKey? = runCatching { ThemeKey(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for API key entities.
 */
@JvmInline
value class ApiKeyKey(override val value: UUID) : UuidKey<ApiKeyKey> {
    companion object {
        fun generate(): ApiKeyKey = ApiKeyKey(UUIDv7.generate())
        fun of(value: UUID): ApiKeyKey = ApiKeyKey(value)
        fun of(value: String): ApiKeyKey = ApiKeyKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Typed ID for User entities.
 */
@JvmInline
value class UserKey(override val value: UUID) : UuidKey<UserKey> {
    companion object {
        fun generate(): UserKey = UserKey(UUIDv7.generate())
        fun of(value: UUID): UserKey = UserKey(value)
        fun of(value: String): UserKey = UserKey(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
