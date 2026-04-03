package app.epistola.suite.catalog

import app.epistola.suite.common.ids.SlugKey
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

@JvmInline
value class CatalogKey(@JsonValue override val value: String) : SlugKey<CatalogKey> {
    init {
        require(value.length in 3..50) {
            "Catalog ID must be 3-50 characters, got ${value.length}"
        }
        require(SLUG_PATTERN.matches(value)) {
            "Catalog ID must match pattern: start with letter, contain only lowercase letters, numbers, and non-consecutive hyphens, and not end with hyphen"
        }
    }

    companion object {
        private val SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")

        fun of(value: String): CatalogKey = CatalogKey(value)

        @JvmStatic
        @JsonCreator
        fun fromJson(value: String): CatalogKey = CatalogKey(value)

        fun validateOrNull(value: String): CatalogKey? = runCatching { CatalogKey(value) }.getOrNull()
    }
}
