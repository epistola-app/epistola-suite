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
sealed interface EntityId<T : EntityId<T, V>, V : Any> {
    val value: V
}

/**
 * Marker interface for slug-based IDs (String value).
 * Used for human-readable, URL-safe identifiers.
 */
sealed interface SlugId<T : SlugId<T>> : EntityId<T, String>

/**
 * Marker interface for UUID-based IDs.
 * Used for machine-generated unique identifiers.
 */
sealed interface UuidId<T : UuidId<T>> : EntityId<T, UUID>

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
value class TenantId(override val value: String) : SlugId<TenantId> {
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

        fun of(value: String): TenantId = TenantId(value)

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): TenantId? = runCatching { TenantId(value) }.getOrNull()
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
value class TemplateId(override val value: String) : SlugId<TemplateId> {
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
            "default",
            "new",
            "create",
            "edit",
            "delete",
        )

        fun of(value: String): TemplateId = TemplateId(value)

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): TemplateId? = runCatching { TemplateId(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for TemplateVariant entities.
 */
@JvmInline
value class VariantId(override val value: String) : SlugId<VariantId> {
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
            "default", "new", "create", "edit", "delete",
        )

        fun of(value: String): VariantId = VariantId(value)

        fun validateOrNull(value: String): VariantId? = runCatching { VariantId(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for TemplateVersion entities.
 * Uses auto-incrementing integers (1-200) per variant instead of UUIDs.
 * The version ID IS the version number - no separate version_number field.
 */
@JvmInline
value class VersionId(override val value: Int) : EntityId<VersionId, Int> {
    init {
        require(value in MIN_VERSION..MAX_VERSION) {
            "Version ID must be between $MIN_VERSION and $MAX_VERSION, got $value"
        }
    }

    override fun toString(): String = value.toString()

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 200

        fun of(value: Int): VersionId = VersionId(value)

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
value class EnvironmentId(override val value: String) : SlugId<EnvironmentId> {
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

        fun of(value: String): EnvironmentId = EnvironmentId(value)

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): EnvironmentId? = runCatching { EnvironmentId(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for Document entities.
 */
@JvmInline
value class DocumentId(override val value: UUID) : UuidId<DocumentId> {
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
value class GenerationRequestId(override val value: UUID) : UuidId<GenerationRequestId> {
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
value class GenerationItemId(override val value: UUID) : UuidId<GenerationItemId> {
    companion object {
        fun generate(): GenerationItemId = GenerationItemId(UUIDv7.generate())
        fun of(value: UUID): GenerationItemId = GenerationItemId(value)
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
value class ThemeId(override val value: String) : SlugId<ThemeId> {
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

        fun of(value: String): ThemeId = ThemeId(value)

        /**
         * Validates a slug without throwing.
         * Returns null if invalid, or the validated slug if valid.
         */
        fun validateOrNull(value: String): ThemeId? = runCatching { ThemeId(value) }.getOrNull()
    }

    override fun toString(): String = value
}

/**
 * Typed ID for User entities.
 */
@JvmInline
value class UserId(override val value: UUID) : UuidId<UserId> {
    companion object {
        fun generate(): UserId = UserId(UUIDv7.generate())
        fun of(value: UUID): UserId = UserId(value)
        fun of(value: String): UserId = UserId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
