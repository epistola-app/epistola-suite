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
// so the embedded Tomcat (CVE-2026-41293 / CVE-2026-43512) must be pinned to 11.0.22 here too
// until the Spring Boot BOM catches up. Read by io.spring.dependency-management.
extra["tomcat.version"] = "11.0.22"

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
    buildInfo()
}

// CycloneDX SBOM for the worker's runtime classpath, embedded into the jar for Docker.
tasks.cyclonedxDirectBom {
    inputs.files(configurations.runtimeClasspath)
    projectType = Component.Type.APPLICATION
    includeBomSerialNumber = true
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("sbom/bom.json").get().asFile
}

tasks.processResources {
    dependsOn(tasks.cyclonedxDirectBom)
    from(layout.buildDirectory.file("sbom/bom.json")) {
        into("META-INF/sbom")
    }
}

// Docker image. Reuses the exact same fonts run image the suite builds (fontconfig +
// DejaVu fonts are required for AWT/iText font rendering) — built from the shared
// apps/epistola/docker/run-image Dockerfile rather than a copy.
val buildRunImage by tasks.registering(Exec::class) {
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
