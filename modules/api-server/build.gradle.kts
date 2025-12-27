plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

// Use the bundled spec from api-spec module
val bundledSpec = project(":modules:api-spec").layout.buildDirectory.file("bundled-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated")

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set(bundledSpec.get().asFile.absolutePath)
    outputDir.set(generatedDir.get().asFile.absolutePath)

    apiPackage.set("app.epistola.api")
    modelPackage.set("app.epistola.api.model")
    invokerPackage.set("app.epistola.api")

    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useBeanValidation" to "true",
            "dateLibrary" to "java8-localdatetime",
            "serializationLibrary" to "jackson",
            "enumPropertyNaming" to "UPPERCASE",
            "skipDefaultInterface" to "true",
            "exceptionHandler" to "false",
            "gradleBuildFile" to "false",
            "documentationProvider" to "none",
            "useJakartaEe" to "true",
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
