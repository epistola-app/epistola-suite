plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
}

// Module containing OpenAPI specification and generated server interfaces.
// The OpenAPI YAML files serve as the source of truth for API contracts.
// Generated interfaces are used by REST controllers in the main application.

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
    implementation(project(":modules:template-model"))
}

val specDir = file("src/main/resources/openapi")
val bundledSpec = layout.buildDirectory.file("bundled-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated")
val generatedResources = layout.buildDirectory.dir("generated-resources")

// ========== OpenAPI Spec Management ==========

// Task to bundle the modular OpenAPI spec into a single file
val bundleOpenApiSpec by tasks.registering(Exec::class) {
    description = "Bundle modular OpenAPI spec into a single file using Redocly CLI"
    group = "openapi"

    workingDir = rootDir
    commandLine(MiseShims.pnpm, "--filter", "@epistola/rest-api", "bundle")

    inputs.dir(specDir)
    outputs.file(bundledSpec)
}

// Task to lint the OpenAPI spec
val lintOpenApiSpec by tasks.registering(Exec::class) {
    description = "Lint OpenAPI spec using Redocly CLI"
    group = "openapi"

    workingDir = rootDir
    commandLine(MiseShims.pnpm, "--filter", "@epistola/rest-api", "lint")

    inputs.dir(specDir)
}

// Task to generate static HTML documentation using Redoc
val generateOpenApiDocs by tasks.registering(Exec::class) {
    description = "Generate static HTML API documentation using Redoc"
    group = "openapi"

    workingDir = rootDir
    commandLine(MiseShims.pnpm, "--filter", "@epistola/rest-api", "docs")

    inputs.dir(specDir)
    outputs.dir(generatedResources)
}

// ========== Server Interface Generation ==========

openApiGenerate {
    generatorName.set("kotlin-spring")
    inputSpec.set(bundledSpec.map { it.asFile.absolutePath })
    outputDir.set(generatedDir.map { it.asFile.absolutePath })

    apiPackage.set("app.epistola.api")
    modelPackage.set("app.epistola.api.model")
    invokerPackage.set("app.epistola.api")

    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useBeanValidation" to "true",
            "useTags" to "true", // Generate separate interface per tag
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

    // Use ObjectNode for generic objects to properly handle null values
    importMappings.set(
        mapOf(
            "ObjectNode" to "tools.jackson.databind.node.ObjectNode",
        ),
    )

    typeMappings.set(
        mapOf(
            "object" to "ObjectNode",
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

// Include generated static resources (Redoc HTML) in the jar
// Spring Boot will serve /api-docs/index.html automatically
sourceSets {
    main {
        resources {
            srcDir(generatedResources)
        }
    }
}

// ========== Task Dependencies ==========

// Ensure spec is bundled before generation
tasks.named("openApiGenerate") {
    dependsOn(bundleOpenApiSpec)
}

// Ensure docs are generated before processing resources
tasks.named("processResources") {
    dependsOn(generateOpenApiDocs)
}

// Ensure code is generated before compilation
tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

// Lint as part of check
tasks.named("check") {
    dependsOn(lintOpenApiSpec)
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
