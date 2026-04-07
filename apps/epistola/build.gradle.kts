import org.cyclonedx.model.Component

plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.graalvm.buildtools.native") apply false
    id("org.cyclonedx.bom")
}

// Conditionally apply native plugin only when building native images
val buildNativeImage = providers.gradleProperty("nativeImage").orNull?.toBoolean() ?: false
if (buildNativeImage) {
    apply(plugin = "org.graalvm.buildtools.native")
}

dependencies {
    // Core business logic module (includes template-model, generation transitively)
    implementation(project(":modules:epistola-core"))

    // Feedback module (feedback system with GitHub integration)
    implementation(project(":modules:feedback"))

    // Catalog module (catalog exchange for sharing templates)
    implementation(project(":modules:epistola-catalog"))

    // Load test module (load testing functionality)
    implementation(project(":modules:loadtest"))

    // REST API module (controllers for external systems)
    implementation(project(":modules:rest-api"))

    // UI/Frontend modules
    implementation(project(":modules:editor"))

    // Spring Boot - UI layer concerns
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.commonmark:commonmark:0.22.0")

    // HTMX for dynamic UI
    implementation(libs.htmx.spring.boot.thymeleaf)

    // Flyway for migrations (app deployment concern)
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Thymeleaf extras
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    // Jackson for Thymeleaf
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Development
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Runtime dependencies
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    // Annotation processors
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Testing
    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-micrometer-tracing-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.playwright)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Enable BuildProperties bean by generating build-info.properties
springBoot {
    buildInfo()
}

// Configure CycloneDX SBOM generation for backend dependencies
tasks.cyclonedxDirectBom {
    // CycloneDx resolves the runtime classpath but doesn't declare implicit dependencies
    // on the project jar tasks that produce those artifacts. Declaring the runtimeClasspath
    // as an input lets Gradle infer the correct task dependencies automatically.
    inputs.files(configurations.runtimeClasspath)
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("sbom/bom.json").get().asFile
}

tasks.processResources {
    // Copy SBOM to JAR resources for Docker embedding
    dependsOn(tasks.cyclonedxDirectBom)
    from(layout.buildDirectory.file("sbom/bom.json")) {
        into("META-INF/sbom")
    }
    // Copy design-system assets so Spring Boot serves them at /design-system/*
    from(rootProject.file("modules/design-system")) {
        include("*.css", "icons.svg")
        into("static/design-system")
    }
    // Copy changelog markdown into app resources
    from(rootProject.file("CHANGELOG.md")) {
        into("changelog")
    }
}

// Convenience task for generating SBOM standalone
tasks.register("generateSbom") {
    group = "verification"
    description = "Generate CycloneDX SBOM for backend dependencies"
    dependsOn(tasks.cyclonedxDirectBom)
}

// Docker image configuration
// Default: JVM image (recommended, stable)
// Use -PnativeImage=true to build a native image (currently broken due to JDBI/Kotlin reflection)

// Build a custom run image based on run-noble-base with only fontconfig and DejaVu fonts added.
// This cuts ~565MB compared to the full run image while still supporting Java AWT font rendering.
val buildRunImage by tasks.registering(Exec::class) {
    group = "docker"
    description = "Build custom CNB run image with fontconfig and fonts"
    commandLine("docker", "build", "-t", "epistola-run:noble", file("docker/run-image").absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    dependsOn(buildRunImage)
    runImage.set("epistola-run:noble")
    pullPolicy.set(org.springframework.boot.buildpack.platform.build.PullPolicy.IF_NOT_PRESENT)
    environment.set(
        mapOf(
            "BP_JVM_VERSION" to "25",
        ),
    )
}
