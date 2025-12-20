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
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}

node {
    download.set(false) // relies on PATH (github actions, mise)
}

tasks.named<com.github.gradle.node.npm.task.NpmTask>("npmInstall") {
    inputs.file("package.json")
    inputs.file("package-lock.json").optional()
}

val npmBuild by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.file("package.json")
    inputs.file("vite.config.ts")
    inputs.file("tsconfig.json")
    outputs.dir("dist")
}

val copyDistToResources by tasks.registering(Copy::class) {
    dependsOn(npmBuild)
    from("dist")
    into(layout.buildDirectory.dir("resources/main/META-INF/resources/editor"))
}

tasks.named("processResources") {
    dependsOn(copyDistToResources)
}

val npmSbom by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "sbom"))
    inputs.file("package.json")
    inputs.file("package-lock.json").optional()
    outputs.file(layout.buildDirectory.file("sbom.json"))
}
