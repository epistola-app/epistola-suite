plugins {
    `java-library`
    id("com.github.node-gradle.node")
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        // TODO: figure out how we can do this another way
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}

node {
    download.set(false) // relies on PATH (github actions, mise)
    nodeProjectDir.set(file("${rootProject.projectDir}"))
}

val pnpmBuild by tasks.registering(com.github.gradle.node.pnpm.task.PnpmTask::class) {
    dependsOn(tasks.named("pnpmInstall"))
    args.set(listOf("--filter", "@epistola/editor", "build"))
    inputs.dir("src")
    inputs.file("package.json")
    inputs.file("vite.config.ts")
    inputs.file("tsconfig.json")
    outputs.dir("dist")
}

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(pnpmBuild)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/editor"))
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}

val pnpmSbom by tasks.registering(com.github.gradle.node.pnpm.task.PnpmTask::class) {
    dependsOn(tasks.named("pnpmInstall"))
    args.set(listOf("--filter", "@epistola/editor", "sbom"))
    inputs.file("package.json")
    outputs.file(layout.buildDirectory.file("sbom.json"))
}
