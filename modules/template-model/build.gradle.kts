plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Jackson annotations for JSON serialization (Jackson 3 uses Jackson 2 annotations)
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")
}
