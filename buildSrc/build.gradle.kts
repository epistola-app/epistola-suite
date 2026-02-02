// Note: The kotlin-dsl plugin uses Gradle's embedded Kotlin version, which may not yet
// support the latest JVM target. If you see a warning like "Kotlin does not yet support
// JDK XX target, falling back to JVM_YY", this only affects buildSrc compilation, not the
// application code. The application itself uses Kotlin 2.3.0+ which supports the latest JVM.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
