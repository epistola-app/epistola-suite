package app.epistola.suite.htmx

import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.validation.DuplicateIdException
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.formMessage
import org.springframework.web.servlet.function.ServerRequest

/**
 * Marker annotation for form validation DSL scope control.
 */
@DslMarker
annotation class FormDsl

/**
 * Validator function that may accumulate errors.
 * Return true if validation passed, false if failed (error message will be used).
 */
typealias Validator = (value: String) -> Boolean

/**
 * Specification for a single form field with validation rules.
 */
@FormDsl
class FieldSpec(val fieldName: String) {
    private val validators = mutableListOf<Pair<Validator, String>>()
    var required: Boolean = false
    var asInt: Boolean = false
    var asAttributeId: Boolean = false
    var asCatalogId: Boolean = false
    var asCodeListId: Boolean = false
    var asEnvironmentId: Boolean = false
    var asStencilId: Boolean = false
    var asTemplateId: Boolean = false
    var asThemeId: Boolean = false
    var asVariantId: Boolean = false
    var asVersionId: Boolean = false
    var asTenantId: Boolean = false

    /**
     * Mark this field as required.
     */
    fun required() {
        this.required = true
    }

    /**
     * Add a pattern validation rule.
     */
    fun pattern(regex: String) {
        val pattern = Regex(regex)
        val capitalizedFieldName = fieldName.replaceFirstChar { it.uppercase() }
        validators.add(
            Pair(
                { value -> pattern.matches(value) },
                "Invalid format for $capitalizedFieldName",
            ),
        )
    }

    /**
     * Add a maximum length validation rule.
     */
    fun maxLength(max: Int) {
        val capitalizedFieldName = fieldName.replaceFirstChar { it.uppercase() }
        validators.add(
            Pair(
                { value -> value.length <= max },
                "$capitalizedFieldName must not exceed $max characters",
            ),
        )
    }

    /**
     * Add a minimum length validation rule.
     */
    fun minLength(min: Int) {
        val capitalizedFieldName = fieldName.replaceFirstChar { it.uppercase() }
        validators.add(
            Pair(
                { value -> value.length >= min },
                "$capitalizedFieldName must be at least $min characters",
            ),
        )
    }

    /**
     * Add a minimum value validation rule (for numeric strings).
     */
    fun min(minimum: Int) {
        val capitalizedFieldName = fieldName.replaceFirstChar { it.uppercase() }
        validators.add(
            Pair(
                { value ->
                    val num = value.toIntOrNull()
                    num != null && num >= minimum
                },
                "$capitalizedFieldName must be at least $minimum",
            ),
        )
    }

    /**
     * Add a maximum value validation rule (for numeric strings).
     */
    fun max(maximum: Int) {
        val capitalizedFieldName = fieldName.replaceFirstChar { it.uppercase() }
        validators.add(
            Pair(
                { value ->
                    val num = value.toIntOrNull()
                    num != null && num <= maximum
                },
                "$capitalizedFieldName must not exceed $maximum",
            ),
        )
    }

    /**
     * Mark this field to be coerced to an Int.
     */
    fun asInt() {
        this.asInt = true
    }

    /**
     * Mark this field to be validated as an AttributeId.
     */
    fun asAttributeId() {
        this.asAttributeId = true
    }

    /**
     * Mark this field to be validated as an EnvironmentId.
     */
    fun asEnvironmentId() {
        this.asEnvironmentId = true
    }

    /**
     * Mark this field to be validated as a CatalogId.
     */
    fun asCatalogId() {
        this.asCatalogId = true
    }

    /**
     * Mark this field to be validated as a CodeListId.
     */
    fun asCodeListId() {
        this.asCodeListId = true
    }

    /**
     * Mark this field to be validated as a StencilId.
     */
    fun asStencilId() {
        this.asStencilId = true
    }

    /**
     * Mark this field to be validated as a TemplateId.
     */
    fun asTemplateId() {
        this.asTemplateId = true
    }

    /**
     * Mark this field to be validated as a ThemeId.
     */
    fun asThemeId() {
        this.asThemeId = true
    }

    /**
     * Mark this field to be validated as a VariantId.
     */
    fun asVariantId() {
        this.asVariantId = true
    }

    /**
     * Mark this field to be validated as a VersionId.
     */
    fun asVersionId() {
        this.asVersionId = true
    }

    /**
     * Mark this field to be validated as a TenantId.
     */
    fun asTenantId() {
        this.asTenantId = true
    }

    /**
     * Run all validators on the given value.
     * Returns error message if validation failed, null if all passed.
     */
    internal fun validate(value: String): String? {
        // Capitalize field name for error messages
        val capitalizedFieldName = fieldName.replaceFirstChar { it.uppercase() }

        // Check required
        if (required && value.isBlank()) {
            return "$capitalizedFieldName is required"
        }

        // Skip further validation if not required and empty
        if (!required && value.isBlank()) {
            return null
        }

        // Run custom validators
        for ((validator, errorMsg) in validators) {
            if (!validator(value)) {
                return errorMsg
            }
        }

        return null
    }
}

/**
 * Result of form binding - contains form data and validation errors.
 * Supports operator overloading to spread into model maps.
 */
class FormData(
    val formData: Map<String, String>,
    val errors: Map<String, String>,
) {
    /**
     * Check if there are any validation errors.
     */
    fun hasErrors(): Boolean = errors.isNotEmpty()

    /**
     * Get field value by name.
     */
    operator fun get(fieldName: String): String = formData[fieldName] ?: ""

    /**
     * Spread this form data into a model map.
     * Returns a map with "formData" and "errors" keys.
     */
    operator fun unaryPlus(): Map<String, Any> = mapOf(
        "formData" to formData,
        "errors" to errors,
    )

    /**
     * Get a typed value with coercion.
     */
    fun <T> getAs(fieldName: String, converter: (String) -> T?): T? {
        val value = formData[fieldName] ?: return null
        return if (value.isBlank()) null else converter(value)
    }

    /**
     * Get integer value.
     */
    fun getInt(fieldName: String): Int? = getAs(fieldName) { it.toIntOrNull() }

    /**
     * Get as EnvironmentId.
     */
    fun getEnvironmentId(fieldName: String): app.epistola.suite.common.ids.EnvironmentKey? = getAs(fieldName) { EnvironmentKey.validateOrNull(it) }

    /**
     * Get as TemplateId.
     */
    fun getTemplateId(fieldName: String): app.epistola.suite.common.ids.TemplateKey? = getAs(fieldName) { TemplateKey.validateOrNull(it) }

    /**
     * Get as VariantId.
     */
    fun getVariantId(fieldName: String): app.epistola.suite.common.ids.VariantKey? = getAs(fieldName) { VariantKey.validateOrNull(it) }

    /**
     * Get as VersionId.
     */
    fun getVersionId(fieldName: String): app.epistola.suite.common.ids.VersionKey? = getAs(fieldName) { it.toIntOrNull()?.let { v -> VersionKey.of(v) } }

    /**
     * Get as TenantId.
     */
    fun getTenantId(fieldName: String): app.epistola.suite.common.ids.TenantKey? = getAs(fieldName) { TenantKey.validateOrNull(it) }
}

/**
 * Builder for form validation.
 * Collects field specifications and validates against request parameters.
 */
@FormDsl
class FormBuilder {
    private val specs = mutableMapOf<String, FieldSpec>()

    /**
     * Define a field to validate.
     */
    fun field(fieldName: String, spec: FieldSpec.() -> Unit) {
        val fieldSpec = FieldSpec(fieldName)
        fieldSpec.spec()
        specs[fieldName] = fieldSpec
    }

    /**
     * Validate the request parameters against the defined field specs.
     */
    internal fun validate(params: Map<String, String>): FormData {
        val errors = mutableMapOf<String, String>()
        // Start with all submitted params, then overlay validated fields
        val formData = params.toMutableMap()

        for ((fieldName, spec) in specs) {
            val value = params[fieldName]?.trim().orEmpty()
            formData[fieldName] = value

            // Run field validation
            val error = spec.validate(value)
            if (error != null) {
                errors[fieldName] = error
            }

            // Domain ID validation.
            //
            // Skipped when a field rule already failed. Every key these validators
            // wrap (EntityKey.kt) restates the same pattern + length rules the field
            // specs declare — AttributeKey/CatalogKey/TemplateKey/StencilKey 3..50,
            // ThemeKey 3..20, CodeListKey 3..64, all on the same SLUG_PATTERN — so a
            // failure here is almost always a second opinion on a failure already
            // recorded above. Overwriting would replace a specific, actionable
            // message ("Slug must be at least 3 characters") with a generic one
            // ("Invalid theme ID format"). What these checks genuinely ADD beyond the
            // specs is the reserved-word rule on TemplateKey/StencilKey ("new",
            // "edit", "admin", …), and that only ever fires when the format and
            // length rules have already passed — i.e. exactly when this block runs.
            if (value.isNotBlank() && errors[fieldName] == null) {
                when {
                    spec.asAttributeId -> {
                        if (AttributeKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid attribute ID format"
                        }
                    }
                    spec.asEnvironmentId -> {
                        if (EnvironmentKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid environment ID format"
                        }
                    }
                    spec.asCatalogId -> {
                        if (CatalogKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid catalog ID format"
                        }
                    }
                    spec.asCodeListId -> {
                        if (CodeListKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid code-list ID format"
                        }
                    }
                    spec.asStencilId -> {
                        if (StencilKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid stencil ID format"
                        }
                    }
                    spec.asTemplateId -> {
                        if (TemplateKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid template ID format"
                        }
                    }
                    spec.asThemeId -> {
                        if (ThemeKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid theme ID format"
                        }
                    }
                    spec.asVariantId -> {
                        if (VariantKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid variant ID format"
                        }
                    }
                    spec.asVersionId -> {
                        if (value.toIntOrNull() == null) {
                            errors[fieldName] = "Version must be a number"
                        }
                    }
                    spec.asTenantId -> {
                        if (TenantKey.validateOrNull(value) == null) {
                            errors[fieldName] = "Invalid tenant ID format"
                        }
                    }
                    spec.asInt -> {
                        if (value.toIntOrNull() == null) {
                            errors[fieldName] = "${fieldName.replaceFirstChar { it.uppercase() }} must be a number"
                        }
                    }
                }
            }
        }

        return FormData(formData, errors)
    }
}

/**
 * Create a form validator from a request.
 * Usage:
 * ```kotlin
 * val form = request.form {
 *     field("slug")  { required(); pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$") }
 *     field("name")  { required(); maxLength(100) }
 * }
 * if (form.hasErrors()) {
 *     return ServerResponse.ok().page("new-page") { +form }
 * }
 * ```
 */
fun ServerRequest.form(spec: FormBuilder.() -> Unit): FormData {
    val builder = FormBuilder()
    builder.spec()

    val params = mutableMapOf<String, String>()
    this.params().forEach { (key, values) ->
        params[key] = values.firstOrNull().orEmpty()
    }

    return builder.validate(params)
}

/**
 * Execute a command and catch domain exceptions, mapping them to form errors.
 * Usage:
 * ```kotlin
 * val result = form.executeOrFormError {
 *     CreateEnvironment(id = envId, tenantId = tenantId, name = name).execute()
 * }
 * if (result.hasErrors()) {
 *     return ServerResponse.ok().page("environments/new") { +result }
 * }
 * ```
 */
fun <T> FormData.executeOrFormError(block: () -> T): FormData = try {
    block()
    this
} catch (e: ValidationException) {
    FormData(
        this.formData,
        this.errors.toMutableMap().apply { put(e.field, e.message) },
    )
} catch (e: DuplicateIdException) {
    val field = when (e.entityType) {
        "environment" -> "slug"
        "stencil" -> "slug"
        "template" -> "slug"
        "tenant" -> "slug"
        "theme" -> "slug"
        "attribute" -> "slug"
        "catalog" -> "slug"
        "code-list" -> "slug"
        else -> "id"
    }
    FormData(
        this.formData,
        this.errors.toMutableMap().apply { put(field, e.formMessage) },
    )
}
