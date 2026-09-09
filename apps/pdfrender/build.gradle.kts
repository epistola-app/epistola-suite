import org.cyclonedx.model.Component

plugins {
    id("epistola-kotlin-conventions")
    id("epistola-kover-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.cyclonedx.bom")
}

// Security override: mirrors apps:epistola. epistola-core pulls spring-boot-starter-web,
// so the embedded Tomcat (CVE-2026-65182 / CVE-2026-65905 / CVE-2026-68525) must be pinned to
// 11.0.25 here too until the Spring Boot BOM catches up. Read by io.spring.dependency-management.
extra["tomcat.version"] = "11.0.25"

dependencies {
    // The whole render/job pipeline (JobPoller, StaleJobRecovery, DocumentGenerationExecutor,
    // GenerationService) plus the pure PDF renderer, JDBI and Postgres come transitively from
    // epistola-core. This is the ONLY project dependency — no UI (epistola-web/editor/thymeleaf),
    // no REST (rest-api), no MCP, no commercial support tier. Fewer modules on the classpath is
    // exactly what makes this a slim worker: those beans never get component-scanned.
    implementation(project(":modules:epistola-core"))

    // Health/readiness probes for the container. No security starter: this is a headless
    // internal worker with no controllers of its own.
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(project(":modules:testing"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Enable the BuildProperties bean (build-info.properties) so the cluster node registry can
// report this worker's version, same as apps:epistola.
springBoot {
    buildInfo {
        // See apps:epistola: a build time makes bootBuildInfo and everything
        // downstream of resources/main never up-to-date, and nothing reads it.
        excludes.add("time")
    }
}

// CycloneDX SBOM for the worker's runtime classpath, embedded into the jar for Docker.
tasks.cyclonedxDirectBom {
    inputs.files(configurations.runtimeClasspath)
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("sbom/bom.json").get().asFile
}

// Embedded at archive level, not via processResources, so compiling and testing
// this app never waits for the CycloneDX run (see apps:epistola).
val sbomJson = tasks.cyclonedxDirectBom.map { it.jsonOutput }
tasks.named<Jar>("jar") {
    from(sbomJson) {
        into("META-INF/sbom")
    }
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Application classpath location inside the boot jar, as before (see apps:epistola).
    from(sbomJson) {
        into("BOOT-INF/classes/META-INF/sbom")
    }
}

// Docker image. Reuses the exact same fonts run image the suite builds (fontconfig +
// DejaVu fonts are required for AWT/iText font rendering) — built from the shared
// apps/epistola/docker/run-image Dockerfile rather than a copy.
val buildRunImage = tasks.register<Exec>("buildRunImage") {
    group = "docker"
    description = "Build custom CNB run image with fontconfig and fonts"
    commandLine(
        "docker",
        "build",
        "-t",
        "epistola-run:noble",
        rootProject.file("apps/epistola/docker/run-image").absolutePath,
    )
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
