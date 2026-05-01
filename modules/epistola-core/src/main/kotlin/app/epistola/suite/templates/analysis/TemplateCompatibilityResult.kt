package app.epistola.suite.templates.analysis

/**
 * Result of checking a template version's compatibility with a new contract schema.
 */
data class TemplateCompatibilityResult(
    val compatible: Boolean,
    val incompatibilities: List<FieldIncompatibility>,
)

data class FieldIncompatibility(
    val path: String,
    val reason: IncompatibilityReason,
    val description: String,
)

enum class IncompatibilityReason {
    FIELD_REMOVED,
    TYPE_CHANGED,
}
