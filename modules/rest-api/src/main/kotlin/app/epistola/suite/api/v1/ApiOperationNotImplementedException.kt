package app.epistola.suite.api.v1

class ApiOperationNotImplementedException(
    val operation: String,
) : RuntimeException("API operation '$operation' is not implemented")
