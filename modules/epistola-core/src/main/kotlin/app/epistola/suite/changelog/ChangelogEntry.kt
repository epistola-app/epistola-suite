package app.epistola.suite.changelog

data class ChangelogEntry(
    val version: String,
    val date: String,
    val html: String,
    val summary: String,
    /** False for the in-progress `[Unreleased]` section (shown as "Upcoming"); true for a released version. */
    val released: Boolean = true,
)
