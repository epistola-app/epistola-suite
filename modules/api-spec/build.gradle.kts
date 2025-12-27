plugins {
    `java-library`
}

// Resource-only module containing OpenAPI specification files.
// These YAML files serve as the source of truth for API contracts.
// The api-server and api-client modules consume the bundled spec for code generation.

val specDir = file("src/main/resources/openapi")
val bundledSpec = layout.buildDirectory.file("bundled-api.yaml")

// Task to bundle the modular OpenAPI spec into a single file
val bundleOpenApiSpec by tasks.registering(Exec::class) {
    description = "Bundle modular OpenAPI spec into a single file using Redocly CLI"
    group = "openapi"

    workingDir = rootDir
    commandLine("pnpm", "openapi:bundle")

    inputs.dir(specDir)
    outputs.file(bundledSpec)
}

// Task to lint the OpenAPI spec
val lintOpenApiSpec by tasks.registering(Exec::class) {
    description = "Lint OpenAPI spec using Redocly CLI"
    group = "openapi"

    workingDir = rootDir
    commandLine("pnpm", "openapi:lint")

    inputs.dir(specDir)
}

tasks.named("check") {
    dependsOn(lintOpenApiSpec)
}
