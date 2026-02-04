package app.epistola.suite.api.v1.shared

internal data class VariantVersionInfo(
    val hasDraft: Boolean,
    val publishedVersions: List<Int>,
)
