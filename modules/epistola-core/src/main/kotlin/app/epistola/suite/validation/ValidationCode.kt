package app.epistola.suite.validation

/**
 * Machine-readable identity for a [ValidationException].
 *
 * These codes used to live as `SCREAMING_SNAKE:` prefixes inside the human
 * message string, which forced consumers (notably the editor) to recover them
 * by regex. They are now a first-class field on [ValidationException]; the
 * message carries only human-readable text.
 *
 * [wire] is the stable string emitted on every surface (REST `ApiExceptionHandler`,
 * the UI draft-save route). It is deliberately decoupled from [name] so the
 * eventual move to RFC 7807 `application/problem+json` can derive a stable
 * `type` URI from it (e.g. `https://errors.epistola.app/<kebab(wire)>`) without
 * another rename. [GENERIC] keeps the historical `"VALIDATION_ERROR"` wire value
 * so the ~80 non-coded validation sites keep their existing contract unchanged.
 */
enum class ValidationCode(val wire: String) {
    /** No specific domain code — generic command/field validation failure. */
    GENERIC("VALIDATION_ERROR"),

    // Node parameter bindings (cross-document + structural shape).
    NODE_PARAMETER_BINDING_UNKNOWN("NODE_PARAMETER_BINDING_UNKNOWN"),
    NODE_PARAMETER_BINDING_SYNTAX_INVALID("NODE_PARAMETER_BINDING_SYNTAX_INVALID"),
    NODE_PARAMETER_BINDING_MISSING_REQUIRED("NODE_PARAMETER_BINDING_MISSING_REQUIRED"),
    NODE_PARAMETER_BINDINGS_INVALID_SHAPE("NODE_PARAMETER_BINDINGS_INVALID_SHAPE"),
    NODE_PARAMETER_BINDING_NAME_INVALID("NODE_PARAMETER_BINDING_NAME_INVALID"),
    NODE_PARAMETER_BINDING_EMPTY("NODE_PARAMETER_BINDING_EMPTY"),
    NODE_PARAMS_ALIAS_RESERVED("NODE_PARAMS_ALIAS_RESERVED"),

    // Parameter schema declaration.
    PARAMETER_SCHEMA_INVALID_TYPE("PARAMETER_SCHEMA_INVALID_TYPE"),
    PARAMETER_REQUIRED_UNKNOWN("PARAMETER_REQUIRED_UNKNOWN"),
    PARAMETER_NAME_INVALID("PARAMETER_NAME_INVALID"),
    PARAMETER_NAME_RESERVED("PARAMETER_NAME_RESERVED"),
    PARAMETER_TYPE_UNSUPPORTED("PARAMETER_TYPE_UNSUPPORTED"),
    PARAMETER_DEFAULT_TYPE_MISMATCH("PARAMETER_DEFAULT_TYPE_MISMATCH"),

    // Placeholders / stencil structure.
    PLACEHOLDER_NAME_DUPLICATE("PLACEHOLDER_NAME_DUPLICATE"),
    PLACEHOLDER_NAME_INVALID("PLACEHOLDER_NAME_INVALID"),
    PLACEHOLDER_NESTED_DEFINITION("PLACEHOLDER_NESTED_DEFINITION"),
    PLACEHOLDER_OUTSIDE_STENCIL("PLACEHOLDER_OUTSIDE_STENCIL"),
    STENCIL_RECURSION("STENCIL_RECURSION"),

    // Page-header cardinality / placement.
    PAGEHEADER_TOO_MANY("PAGEHEADER_TOO_MANY"),
    PAGEHEADER_ROOT_MISSING("PAGEHEADER_ROOT_MISSING"),
    PAGEHEADER_NOT_AT_ROOT("PAGEHEADER_NOT_AT_ROOT"),
}
