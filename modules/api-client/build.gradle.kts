plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.openapi.generator)
}

// Note: This module intentionally uses Jackson 2.x (com.fasterxml.jackson) instead of Jackson 3.x
// to maintain compatibility with consumers who haven't migrated to Jackson 3.x yet.
// The main application uses Jackson 3.x (tools.jackson), but this client library is meant
// for external consumers who may still be on Jackson 2.x.

dependencies {
    implementation(libs.spring.web)
    implementation(libs.jackson2.databind)
    implementation(libs.jackson2.kotlin)
    implementation(libs.jackson2.jsr310)
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

// Use the bundled spec from api-spec module
val bundledSpec = project(":modules:api-spec").layout.buildDirectory.file("bundled-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated")

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(bundledSpec.get().asFile.absolutePath)
    outputDir.set(generatedDir.get().asFile.absolutePath)

    apiPackage.set("app.epistola.client.api")
    modelPackage.set("app.epistola.client.model")
    invokerPackage.set("app.epistola.client")

    configOptions.set(
        mapOf(
            "library" to "jvm-spring-restclient",
            "dateLibrary" to "java8-localdatetime",
            "serializationLibrary" to "jackson",
            "enumPropertyNaming" to "UPPERCASE",
            "useSpringBoot3" to "true",
        ),
    )

    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "",
        ),
    )
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
        }
    }
}

// Ensure spec is bundled before generation
tasks.named("openApiGenerate") {
    dependsOn(":modules:api-spec:bundleOpenApiSpec")
}

tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

// Exclude generated code from ktlint and ensure proper task ordering
configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

// ktlint tasks must run after code generation to avoid implicit dependency issues
tasks.named("runKtlintCheckOverMainSourceSet") {
    dependsOn("openApiGenerate")
}

tasks.named("runKtlintFormatOverMainSourceSet") {
    dependsOn("openApiGenerate")
}
