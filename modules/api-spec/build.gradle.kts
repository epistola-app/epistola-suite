plugins {
    `java-library`
}

// Resource-only module containing OpenAPI specification files.
// These YAML files serve as the source of truth for API contracts.
// The api-server and api-client modules consume the bundled spec for code generation.
// The generated Redoc HTML documentation is included as static resources.

val specDir = file("src/main/resources/openapi")
val bundledSpec = layout.buildDirectory.file("bundled-api.yaml")
val generatedResources = layout.buildDirectory.dir("generated-resources")

// Task to bundle the modular OpenAPI spec into a single file
val bundleOpenApiSpec by tasks.registering(Exec::class) {
    description = "Bundle modular OpenAPI spec into a single file using Redocly CLI"
    group = "openapi"

    workingDir = rootDir
    commandLine("pnpm", "--filter", "@epistola/api-spec", "bundle")

    inputs.dir(specDir)
    outputs.file(bundledSpec)
}

// Task to lint the OpenAPI spec
val lintOpenApiSpec by tasks.registering(Exec::class) {
    description = "Lint OpenAPI spec using Redocly CLI"
    group = "openapi"

    workingDir = rootDir
    commandLine("pnpm", "--filter", "@epistola/api-spec", "lint")

    inputs.dir(specDir)
}

// Task to generate static HTML documentation using Redoc
val generateOpenApiDocs by tasks.registering(Exec::class) {
    description = "Generate static HTML API documentation using Redoc"
    group = "openapi"

    workingDir = rootDir
    commandLine("pnpm", "--filter", "@epistola/api-spec", "docs")

    inputs.dir(specDir)
    outputs.dir(generatedResources)
}

// Include generated static resources in the jar
// Spring Boot will serve /api-docs/index.html automatically
sourceSets {
    main {
        resources {
            srcDir(generatedResources)
        }
    }
}

// Ensure docs are generated before processing resources
tasks.named("processResources") {
    dependsOn(generateOpenApiDocs)
}

tasks.named("check") {
    dependsOn(lintOpenApiSpec)
}
