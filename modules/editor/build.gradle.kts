plugins {
    `java-library`
    id("com.github.node-gradle.node")
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
